# PaddleShock AWS matchmaking (rendezvous-only)

Scope: a tiny AWS backend that hands two players a short lobby code so they can exchange public
`ip:port` and connect directly over UDP. Gameplay itself stays peer-to-peer (see
`src/main/java/com/paddleshock/net/`) - nothing here touches game traffic, only lobby-code
brokering.

## What's deployed (account 920394550355, region us-east-1)

- **DynamoDB table** `paddleshock-lobbies` - PK `code` (String), TTL attribute `ttl` (items
  auto-expire ~10 min after creation, so stale lobbies clean themselves up for free).
- **IAM role** `paddleshock-lobby-lambda-role` - trusts `lambda.amazonaws.com`, has
  `AWSLambdaBasicExecutionRole` (CloudWatch Logs) plus an inline policy scoped to
  `PutItem`/`GetItem`/`UpdateItem` on just the `paddleshock-lobbies` table ARN.
- **Lambda function** `paddleshock-lobby` (Node.js 20.x, source in `lobby-lambda/index.mjs`) -
  handles `create` / `join` / `poll` actions via a single JSON-body handler. Verified working via
  direct `aws lambda invoke` (returns a real lobby code, writes to DynamoDB correctly).
- **Lambda Function URL** `https://2mjcwpgb6sesrjy36nm6qxdmpu0wbvrb.lambda-url.us-east-1.on.aws/` -
  `AuthType=NONE` (public/unauthenticated - fine, since it only ever brokers ephemeral,
  non-sensitive lobby codes and ip:port pairs) with a resource policy granting both
  `lambda:InvokeFunctionUrl` and `lambda:InvokeFunction` to `Principal: "*"` (see "Resolved
  blocker" below for why both are required). **Verified live end-to-end** (create/join/poll all
  round-trip correctly).

## Resolved blocker (2026-09-13)

The Function URL initially returned `403 Forbidden` for all anonymous requests despite
`AuthType=NONE` and a resource policy granting `lambda:InvokeFunctionUrl`. Root cause per
[AWS's Function URL auth docs](https://docs.aws.amazon.com/lambda/latest/dg/urls-auth.html):
**"Starting in October 2025, new function URLs will require both `lambda:InvokeFunctionUrl` and
`lambda:InvokeFunction` permissions."** We'd only granted the first. Not an account-age
restriction as initially suspected - fixed by adding the second permission:

```
aws lambda add-permission \
  --function-name paddleshock-lobby \
  --statement-id UrlPolicyInvokeFunction \
  --action lambda:InvokeFunction \
  --principal "*" \
  --invoked-via-function-url \
  --region us-east-1
```

(`--invoked-via-function-url` sets the `lambda:InvokedViaFunctionUrl` condition so this
permission only applies to Function URL calls, not other invocation paths.)

## Protocol

Single POST endpoint, JSON body, `action` field selects behavior:

- `{"action":"create","addr":"<host's public ip:port>"}` -> `{"code":"ABC123"}`
- `{"action":"join","code":"ABC123","addr":"<joiner's public ip:port>"}` -> `{"hostAddr":"..."}`
- `{"action":"poll","code":"ABC123"}` (host polls this) -> `{"joinerAddr": null | "..."}`

## Client-side progress

- **Done (Phase B)**: `StunClient` (`src/main/java/com/paddleshock/net/StunClient.java`) - a
  minimal RFC 5389 STUN binding client. `NetHost`/`NetClient` now run discovery on their own
  `DatagramSocket` at construction, before starting their background receive thread (required -
  STUN discovery does its own blocking `socket.receive()` calls, so it must finish first or it'd
  race the receive thread for incoming packets). Exposed via `getPublicAddress()` on both.
  Live-verified against real Google STUN servers (~70ms, consistent across runs) and regression
  -tested against the full two-instance LAN host/join flow (unaffected).
- **Done (Phase C)**: `LobbyClient` (`src/main/java/com/paddleshock/net/LobbyClient.java`) wraps
  the 3 Lambda actions over `java.net.http.HttpClient`. `NetHost.registerLobby()` and the new
  `NetClient.connectByLobbyCode(String)` (a static factory - it must discover its own public
  address on the real game socket BEFORE it can register as a joiner and learn the host's
  address, so it can't use the existing instance constructor, which needs the host address up
  front) wire this into the actual connect flow. `MultiplayerState`'s JOIN field now accepts
  either an IP:port (unchanged) or a lobby code (detected by the absence of a `:`, normalized to
  uppercase since codes are generated uppercase and DynamoDB keys are case-sensitive); the HOST
  screen shows the LAN address as before plus an "Internet code" once background registration
  completes. All background lobby calls are generation-guarded (an in-flight call superseded by
  a newer attempt, or by leaving the screen, closes its own result instead of leaking a socket).
  **Live-verified**: a real lobby code was generated, entered on a second instance (lowercase, to
  confirm normalization), resolved via the Lambda to the correct host address, and the joiner
  reached "Connecting...". The actual UDP handshake did not complete in this test - see below,
  this is expected, not a Phase C defect.
- **Done (Phase D)**: `NetHost.pollAndPunchUntilJoined(String lobbyCode)` polls the Lambda's
  `poll` action (built in Phase A, unused until now) every 1.5s for up to 30s until a joiner's
  public address appears, then sends toward it every 300ms for up to 8s - reacting to an inbound
  HELLO alone was never enough, since a joiner's first HELLOs can be dropped by the HOST's own
  NAT before the host has sent anything outbound to the joiner. Runs on the same background
  thread that already does lobby registration, continuing after the code is shown so it doesn't
  block the UI. `MultiplayerState` now also resends `NetClient`'s HELLO every 0.3s instead of
  only once at construction, so the joiner is still retrying when the host's punch eventually
  opens a path. Regression-tested: the full LAN (IP:port) host/join flow still works end to end
  with the background punch thread running concurrently, and produced no exceptions.
  **Not independently live-verified for real cross-NAT traversal** - see below.

### A same-machine test can't validate NAT traversal

Both live tests so far (Phase C and D) ran two instances on one machine/network, which cannot
prove hole-punching works: the failure mode is NAT **hairpinning** (routing a packet addressed to
your own public IP back into your own LAN), a distinct router feature from **hole-punching**
between two genuinely different networks - Phase D's active punching does not and cannot fix a
router that lacks hairpin support, because in a same-machine test there's no second NAT for the
punching to actually open. The code path for punching runs correctly (confirmed via logs: no
exceptions, LAN flow unaffected) but its actual real-world effectiveness against a genuine
cross-NAT scenario is unverified. Phase E (two different real networks, e.g. a phone hotspot vs.
home wifi) is the only way to actually validate this.

See the main session's design doc discussion (not committed) for the full phase breakdown
(A: AWS infra, B: STUN client, C: lobby UI, D: active punching [this], E: cross-network QA).

## Redeploying the Lambda after code changes

```
cd aws/lobby-lambda
npm install
# Windows PowerShell:
Compress-Archive -Path index.mjs,node_modules,package.json -DestinationPath function.zip -Force
aws lambda update-function-code --function-name paddleshock-lobby --zip-file fileb://function.zip --region us-east-1
```
