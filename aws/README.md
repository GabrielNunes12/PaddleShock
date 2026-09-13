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

## Not yet built (client side)

- STUN client in `NetHost`/`NetClient`'s existing `DatagramSocket` to discover each side's public
  `ip:port` (the discovery must happen on the same socket/port used for actual game traffic).
- Lobby-code UI in `MultiplayerState` (replacing/augmenting the current IP:port text field).
- `NetHost` becoming an active puncher (sends toward the joiner's public addr once known) instead
  of purely reactive - required for NAT hole-punching to actually open a path both ways.

See the main session's design doc discussion (not committed) for the full phase breakdown
(A: AWS infra [this], B: STUN client, C: lobby UI, D: active punching, E: cross-network QA).

## Redeploying the Lambda after code changes

```
cd aws/lobby-lambda
npm install
# Windows PowerShell:
Compress-Archive -Path index.mjs,node_modules,package.json -DestinationPath function.zip -Force
aws lambda update-function-code --function-name paddleshock-lobby --zip-file fileb://function.zip --region us-east-1
```
