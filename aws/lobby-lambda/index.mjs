import { DynamoDBClient } from "@aws-sdk/client-dynamodb";
import { DynamoDBDocumentClient, PutCommand, UpdateCommand, GetCommand } from "@aws-sdk/lib-dynamodb";

// Rendezvous-only matchmaking for PaddleShock LAN-over-internet play. Gameplay itself stays
// peer-to-peer over UDP (see NetHost/NetClient) - this Lambda only brokers a short lobby code
// to two players so they can exchange public ip:port and start UDP hole-punching. It never sees
// or relays any game traffic.

const TABLE = process.env.TABLE_NAME;
const client = DynamoDBDocumentClient.from(new DynamoDBClient({}));

const CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no ambiguous 0/O/1/I
const LOBBY_TTL_SECONDS = 600;

function randomCode(length = 6) {
    let code = "";
    for (let i = 0; i < length; i++) {
        code += CODE_CHARS[Math.floor(Math.random() * CODE_CHARS.length)];
    }
    return code;
}

function response(statusCode, body) {
    return {
        statusCode,
        headers: { "content-type": "application/json" },
        body: JSON.stringify(body),
    };
}

async function handleCreate(body) {
    if (!body.addr) {
        return response(400, { error: "missing addr" });
    }
    const now = Math.floor(Date.now() / 1000);
    for (let attempt = 0; attempt < 5; attempt++) {
        const code = randomCode();
        try {
            await client.send(new PutCommand({
                TableName: TABLE,
                Item: { code, hostAddr: body.addr, joinerAddr: null, ttl: now + LOBBY_TTL_SECONDS },
                ConditionExpression: "attribute_not_exists(code)",
            }));
            return response(200, { code });
        } catch (e) {
            if (e.name !== "ConditionalCheckFailedException") {
                throw e;
            }
            // code collision - loop and try a new random code
        }
    }
    return response(500, { error: "could not allocate a lobby code, try again" });
}

async function handleJoin(body) {
    if (!body.code || !body.addr) {
        return response(400, { error: "missing code/addr" });
    }
    try {
        const result = await client.send(new UpdateCommand({
            TableName: TABLE,
            Key: { code: body.code },
            UpdateExpression: "SET joinerAddr = :a",
            ConditionExpression: "attribute_exists(code)",
            ExpressionAttributeValues: { ":a": body.addr },
            ReturnValues: "ALL_NEW",
        }));
        return response(200, { hostAddr: result.Attributes.hostAddr });
    } catch (e) {
        if (e.name === "ConditionalCheckFailedException") {
            return response(404, { error: "lobby not found or expired" });
        }
        throw e;
    }
}

async function handlePoll(body) {
    if (!body.code) {
        return response(400, { error: "missing code" });
    }
    const result = await client.send(new GetCommand({ TableName: TABLE, Key: { code: body.code } }));
    if (!result.Item) {
        return response(404, { error: "lobby not found or expired" });
    }
    return response(200, { joinerAddr: result.Item.joinerAddr ?? null });
}

export const handler = async (event) => {
    let body;
    try {
        body = JSON.parse(event.body || "{}");
    } catch {
        return response(400, { error: "malformed JSON body" });
    }

    switch (body.action) {
        case "create": return handleCreate(body);
        case "join": return handleJoin(body);
        case "poll": return handlePoll(body);
        default: return response(400, { error: "unknown action" });
    }
};
