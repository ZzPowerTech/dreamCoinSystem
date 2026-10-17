# dreamCoinSystem — Roadmap

Regra: **1 PR = 1 responsabilidade lógica, funcional e deployável sozinha.** Cada PR em worktree
`.claude/.worktrees/<branch>`, TDD, Conventional Commits, code review antes do merge.

Sprint única de 1 semana (padrão AusTV/plugins), cinco PRs em sequência.

| # | Branch | Entrega | Requisitos | Estado |
|---|---|---|---|---|
| 0 | `main` | Bootstrap: CLAUDE.md, structure.md, specs | — | ✅ |
| 1 | `feature/core-storage` | Scaffold Maven, núcleo puro, Storage + AccountService, conta no login, `/saldo`, CI | COIN-01..06, STO-01..05, CMD-01 | ✅ mergeado (#1) |
| 2 | `feature/vault-economy` | `DreamEconomy` + registro Highest + aviso de provedor concorrente | VLT-01..05 | ✅ mergeado (#2) |
| 3 | `feature/admin-commands` | `/moedas give\|take\|set\|reload` online e offline | ADM-01..04 | ✅ mergeado (#3) |
| 4 | `feature/pay-command` | `/pagar` + `PaymentPolicy` + confirmação + cooldown | PAY-01..09 | ✅ mergeado (#4) |
| 5 | `feature/placeholders` | `DreamCoinsExpansion` + espelho de leitura assíncrona | PH-01..03 | ✅ mergeado (#5) |
| 6 | `fix/placeholder-followups` | Pendências da revisão do PR #5: expansão sai no disable, `has_0`, imports | PH-01..03 | 🔄 PR aberto |

## Definition of Done (todo PR)

- [ ] Testes unitários do que é puro, escritos antes do código (TDD), passando.
- [ ] Testes de regressão provados contra o defeito reconstruído.
- [ ] `mvn clean package` verde local e no CI.
- [ ] `CLAUDE.md` (estado atual) e `structure.md` atualizados.
- [ ] Roteiro manual do PR em `docs/MANUAL-TESTS.md`.
- [ ] Code review sem apontamento crítico.
