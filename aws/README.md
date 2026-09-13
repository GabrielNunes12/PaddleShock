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
  non-sensitive lobby codes and ip:port pairs) with a resource policy granting
  `lambda:InvokeFunctionUrl` to `Principal: "*"`.

## Known blocker (as of 2026-09-13)

The Function URL itself returns a generic `403 Forbidden` for anonymous requests, despite
`AuthType=NONE` and a correctly-attached resource policy (confirmed via `get-function-url-config`
and `get-policy`). This is NOT a propagation delay - retried well past AWS's stated window. Most
likely explanation: AWS applies a default restriction on **public (anonymous) Function URLs for
brand-new accounts** that clears automatically after account review (typically 24-48h),
independent of any IAM/resource policy configuration.

**Decision (per user): wait and retry**, rather than switch to IAM-signed requests (which would
require embedding a real AWS credential in the distributed game client - bad practice) or dig
through the AWS console for an account notice.

**To retest once the wait period has passed:**
```
curl -s -X POST https://2mjcwpgb6sesrjy36nm6qxdmpu0wbvrb.lambda-url.us-east-1.on.aws/ \
  -H "content-type: application/json" \
  -d '{"action":"create","addr":"203.0.113.5:55123"}'
```
Expect `{"code":"XXXXXX"}`. A `{"Message":"Forbidden...` response means it's still blocked.

## Protocol (once the URL is reachable)

Single POST endpoint, JSON body, `action` field selects behavior:

- `{"action":"create","addr":"<host's public ip:port>"}` -> `{"code":"ABC123"}`
- `{"action":"join","code":"ABC123","addr":"<joiner's public ip:port>"}` -> `{"hostAddr":"..."}`
- `{"action":"poll","code":"ABC123"}` (host polls this) -> `{"joinerAddr": null | "..."}`

## Not yet built (client side, once the URL is confirmed reachable)

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
