Done. Here's what changed:

## Summary

The reconnect loop now waits **3,000 ms** between attempts and keeps the outbox.

| File | Change |
|---|---|
| `SessionSocket.kt` | backoff + outbox |
| `ReconnectTest.kt` | 4 new cases |

### Next steps

1. Run `./gradlew :core:network:test`
2. Deploy to the Jetson:
   ```bash
   rsync -av dist/ jetson:~/assistant/frontend/dist/
   sudo systemctl restart agentic-backend.service
   ```
3. Verify on the A300M

> Note: the Jetson has no Node, so build on the laptop.

Let me know if you want me to commit.
