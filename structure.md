# dreamCoinSystem — Estrutura do código-fonte

> Mapa para localizar arquivos sem varrer a árvore inteira. Regenerar quando a arquitetura mudar.
> Estado: PR 3 (`feature/admin-commands`).

```
dreamCoinSystem/
├── pom.xml                          # Maven: Java 25, Paper, shade (drivers + Hikari relocado)
├── CLAUDE.md                        # contexto do projeto (fonte de verdade)
├── structure.md                     # este arquivo
├── .github/workflows/ci.yml         # mvn clean package em PR e na main
├── .specs/
│   ├── project/PROJECT.md           # visão, escopo, decisões
│   ├── project/ROADMAP.md           # PRs incrementais + DoD
│   └── features/coin-core/spec.md   # requisitos rastreáveis e critérios de aceite
├── docs/MANUAL-TESTS.md             # roteiro manual por PR
└── src/
    ├── main/
    │   ├── java/dev/austv/dreamcoins/
    │   │   ├── DreamCoins.java              # JavaPlugin: monta tudo, agenda flush/resync
    │   │   ├── money/                       # núcleo puro — zero Bukkit
    │   │   │   ├── CoinType.java                # ouro/prata/bronze: taxas e aliases fixos
    │   │   │   ├── Money.java                   # um long em bronze; decomposição e aritmética exata
    │   │   │   ├── MoneyParser.java             # "1 ouro 35 bronze" / "2p 1b" → Money ou erro tipado
    │   │   │   ├── MoneyFormatter.java          # forma longa, curta e separador de milhar
    │   │   │   └── VaultAmounts.java            # double do Vault ⇄ bronze (1.0 = 1 prata)
    │   │   ├── account/
    │   │   │   ├── AccountLedger.java           # cache dos online + pendentes; reconciliação por seq (puro)
    │   │   │   ├── AccountService.java          # ledger ⇄ banco ⇄ main thread (sem Bukkit)
    │   │   │   └── AccountListener.java         # pré-login carrega, join entra no cache, quit grava
    │   │   ├── storage/
    │   │   │   ├── Storage.java                 # contrato bloqueante, tudo por delta
    │   │   │   ├── SqlStorage.java              # SQL comum + transações
    │   │   │   ├── SqliteStorage.java           # dialeto SQLite (WAL, pool 1)
    │   │   │   ├── MySqlStorage.java            # dialeto MySQL (timeouts de pool)
    │   │   │   ├── SequencedExecutor.java       # thread única do banco com nº de sequência
    │   │   │   ├── StorageType.java · GuardedResult.java · StoredAccount.java
    │   │   ├── config/
    │   │   │   ├── PluginConfig.java            # config.yml validado (quantias como no jogo)
    │   │   │   └── Messages.java                # messages.yml em MiniMessage + nomes das moedas
    │   │   ├── vault/
    │   │   │   ├── VaultBridge.java             # Vault por UUID, sem Bukkit: cache ou banco esperando
    │   │   │   └── DreamEconomy.java            # implements Economy: nome/jogador → UUID, main thread
    │   │   ├── admin/
    │   │   │   └── AdminOperations.java         # give/take/set por UUID, sem Bukkit (set offline por delta)
    │   │   └── command/
    │   │       ├── BalanceCommand.java          # /saldo [jogador]
    │   │       ├── AdminCommand.java            # /moedas give|take|set|reload
    │   │       └── AmountArgs.java              # quantia a partir de um índice dos args; chave de erro
    │   └── resources/
    │       ├── plugin.yml · config.yml · messages.yml
    └── test/java/dev/austv/dreamcoins/      # JUnit 5, sem servidor
        ├── money/                           # Money, Parser (exemplos do pedido), Formatter, VaultAmounts
        ├── account/                         # AccountLedger (puro) e AccountService (SQLite real)
        ├── storage/SqliteStorageTest.java   # deltas, guarda, dois "servidores" no mesmo banco
        ├── vault/VaultBridgeTest.java       # arredondamento, online × offline, janela de login
        ├── admin/AdminOperationsTest.java   # online/offline, set com drift, BUSY
        ├── command/AmountArgsTest.java
        ├── config/PluginConfigTest.java
        └── ResourcesTest.java               # sem comandos do Essentials; chaves do messages.yml
```
