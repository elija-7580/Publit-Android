Native Android app of Publit (Kotlin + Compose). Read PORTING.md first: rules, layout, milestone status.
Port from the iOS Swift sources named in the milestone row only. Build via GitHub Actions. Update PORTING.md status at the end.
Commit and push after every finished step (not only per milestone): the session limit can end work at any time. Unfinished work is pushed as WIP and marked in PORTING.md.

## Rollen-Agenten (agency-agents)

`.claude/agents/` (im Repo-Wurzelordner) enthält 50 geprüfte Rollen aus msitarzewski/agency-agents (MIT, fester Commit `8ae6164`, Liste in `/.claude/AGENTS-ROLLEN.md`). Wünscht Elija eine Rolle („nimm den reality-checker“) und ist sie nicht als Subagent-Typ verfügbar (etwa weil die Session nicht im Repo-Ordner startet), die Datei lesen und ihren Text als Prompt für einen Agent nutzen. Quellkommentare in den Rollen-Dateien nicht entfernen; neue Rollen nur geprüft und mit Quellkommentar aufnehmen.
