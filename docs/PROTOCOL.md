# iGPSPORT BLE protocol

The wire-protocol spec for the BSC200 — framing, services, CRC,
file-transfer paths, AGPS pre-seed, position-prior injection — lives
in the reverse-engineering reference repo:

→ <https://github.com/makefu/ligpsport/blob/main/docs/PROTOCOL.md>

This Android client is a Kotlin port of the protocol logic in that
repo. The doc is kept there because:

* It's the authoritative source of truth (the Python implementation
  there is what the wire bytes were originally validated against).
* It's not Android-specific — other ports (desktop, web, embedded)
  consume the same doc.

In a local dev checkout where `makefu/ligpsport` is sibling-cloned at
`../ligpsport`, `/home/makefu/r/ligpsport/docs/PROTOCOL.md` is the
on-disk path; agents working in this repo can use either form.
