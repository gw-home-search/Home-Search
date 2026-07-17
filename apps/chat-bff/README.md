# Home Search chat BFF

`apps/chat-bff` is the authenticated public chatbot boundary. It verifies the
user access token, applies the subject-based Redis rate limit, invokes AI with a
bounded timeout, and maps JSON/SSE errors to the public chatbot contract.

`local-runtime.example` contains public-key routing metadata only. Use the same
active `kid` as user-service and mount only the user public key. The signing
private key must never be supplied to this service.

Local integrated startup is owned by `infra/chatbot/run-local-chatbot.sh`. The
runner requires a packaged `build/libs/chat-bff.jar`, validates runtime inputs,
and activates the opt-in gateway overlay only after Compose configuration is
valid.
