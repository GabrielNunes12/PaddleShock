# Tasks

## Save data security follow-up

Local saves (`profile.dat`/`settings.dat`) are AES-256-GCM encrypted, but the key lives in the
client jar (`SaveCrypto.java`) — this stops casual editing in a text editor, not a determined
player willing to decompile the jar and pull the key out.

- [ ] Once there's a server to validate against (leaderboards/Steamworks), move currency/purchase
      truth server-side instead of trusting the local encrypted file for anything that matters
      competitively (leaderboard scores, purchases affecting multiplayer balance).
- [ ] Until then, local-save tampering is an accepted risk for a single-player prototype — no
      further client-side hardening planned (e.g. obfuscation, anti-debug) since it wouldn't
      meaningfully raise the bar for the effort involved.
