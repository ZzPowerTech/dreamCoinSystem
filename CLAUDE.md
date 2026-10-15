# dreamCoinSystem — Contexto para o Claude

## O que é

Economia multi-moedas do servidor **Dream**: três moedas usadas ao mesmo tempo — **ouro**, **prata**
e **bronze** — que se convertem sozinhas, como reais e centavos. O plugin é o **provedor de Economy
do Vault**, então qualquer plugin que fala Vault (lojas, leilões) funciona sem adaptação.

| Moeda | Em bronze | Em prata |
|---|---|---|
| 1 ouro | 100.000 | 1.000 |
| 1 prata | 100 | 1 |
| 1 bronze | 1 | 0,01 |

## Stack

- **Linguagem/runtime:** Java 25 (`maven.compiler.release=25`).
- **Plataforma:** Paper 1.21.11 (`paper-api` como `provided`).
- **APIs:** VaultAPI 1.7.1 (`depend`), PlaceholderAPI 2.12.2 (`softdepend`).
- **Banco:** SQLite (padrão) ou MySQL, via HikariCP.
- **Build:** `mvn clean package` → `target/dreamCoinSystem.jar`.

Versões de dependência fixadas nas que o servidor **roda**, não nas mais recentes — mesma regra do
`ausAchievements`.

## Regras de negócio-chave

1. **O saldo é UM número inteiro em bronze** (`long`). Ouro/prata/bronze são só a decomposição na
   exibição. A "conversão automática" é consequência disso, não código: quem tem 1 ouro e paga 35
   bronze fica com 0 ouro, 999 prata e 65 bronze.
2. **Taxas fixas em código** (`CoinType`). Mudar a taxa depois de ir ao ar reescalaria o saldo de
   todo mundo — por isso não é configurável.
3. **Vault: 1.0 = 1 prata** (`fractionalDigits() = 2`). Bronze é o centavo. Fração abaixo de
   1 bronze: **depósito arredonda para baixo, saque/`has` para cima** — nunca cria nem deixa escapar
   dinheiro.
4. **Só três moedas**: ouro, prata, bronze. "cobre" **não** é alias.
5. **Persistência por delta atômico** (`balance = balance + ?`), nunca por valor absoluto: dois
   servidores no mesmo MySQL somam em vez de se sobrescrever.
6. **Saldo nunca negativo** e com teto absoluto de 999.999.999.999.999 bronze (9.999.999.999 ouro):
   o maior saldo de 15 dígitos, que é o que um `double` devolve idêntico ao Vault.

## Convenções específicas

- **Núcleo puro sem Bukkit** (`dev.austv.dreamcoins.money`): tudo que decide (parse, formatação,
  arredondamento, proteções do `/pagar`) é testável sem servidor.
- **Nunca registrar `/pay`, `/balance`, `/eco`** — colidem com o Essentials.
- **Drivers JDBC não são relocados no shade**; só o HikariCP. `ServicesResourceTransformer`
  obrigatório.
- **Nada de `libraries:` no `plugin.yml`.**
- **O cache de contas só é tocado na main thread**; o writer assíncrono recebe deltas imutáveis.
- Mensagens em `messages.yml` com **MiniMessage**.

## Mapa rápido

- Estrutura do código: `structure.md`
- Spec do projeto: `.specs/project/PROJECT.md` · feature: `.specs/features/coin-core/spec.md`
- Sprints/PRs: `.specs/project/ROADMAP.md`
- Repositório: `ZzPowerTech/dreamCoinSystem`

## Nota de ambiente local

Se um antivírus interceptar HTTPS (Avast, no PC do Murilo), o Maven falha com
`PKIX path building failed`. Correção sem tocar no `cacerts` do sistema:

```bash
keytool -importcert -trustcacerts -noprompt -alias avast-web-shield \
  -file "C:/ProgramData/Avast Software/Avast/wscert.pem" \
  -keystore ./cacerts-avast.jks -storepass changeit
export MAVEN_OPTS="-Djavax.net.ssl.trustStore=$PWD/cacerts-avast.jks -Djavax.net.ssl.trustStorePassword=changeit"
```

## Estado atual

**PR 1 (`feature/core-storage`) — mergeado ([#1](https://github.com/ZzPowerTech/dreamCoinSystem/pull/1)):**
núcleo puro de moedas, `AccountLedger` + `AccountService`, SQLite/MySQL por delta, conta criada no
pré-login, `/saldo`, CI.

**PR 2 (`feature/vault-economy`) — mergeado ([#2](https://github.com/ZzPowerTech/dreamCoinSystem/pull/2)):**
provedor de Economy do Vault (`vault/`).

**PR 3 (`feature/admin-commands`) — atual:** `/moedas give|take|set|reload` (`admin/`,
`command/AdminCommand`, `command/AmountArgs`). Próximo: PR 4 (`feature/pay-command`).

### Decisões de design do PR 3

- **`AdminOperations` sem Bukkit**, testado contra SQLite; o comando só faz parse, resolução do
  alvo e mensagens.
- **Online e offline pelo mesmo caminho do Vault**: conta no cache pelo cache; fora dele pelo
  `applyOffline` (reserva na retirada, órfão no crédito).
- **`take` acima do saldo é recusado**, nunca zera em silêncio — o admin vê quanto o jogador tem.
- **`set` offline não grava valor absoluto.** Lê o saldo, aplica a diferença por delta e confere o
  resultado; se outra escrita entrou no meio (Vault, outro servidor), avisa o admin com o saldo real
  (`drifted`) em vez de dizer "pronto". `set` com reserva pendente devolve `BUSY` (regra do PR 1).
- **"0" só vale no `set`** (`AmountArgs`, também "0 ouro"); dar, tirar ou pagar zero é erro de
  digitação. O parser valida o número antes de exigir a moeda: `/moedas give fox 0` diz "maior que
  zero", não "faltou a moeda".
- **Toda alteração vai ao console** com autor, alvo, valor e resultado: é dinheiro criado ou
  destruído por fora da economia, e "quem me deu isto?" vira pergunta de suporte.
- **Aviso ao jogador online** quando a staff mexe no saldo dele (`notify.*`, desligável com "").
- **`reload` aplica a quente** saldo inicial, teto e mensagens; banco e intervalos das tarefas
  pedem reinício (trocar de banco com deltas em voo seria pedir para perder dinheiro).
- **`switch` de comando sobre `Outcome` precisa cobrir `UNAVAILABLE`**: switch com setas não é
  obrigado a ser exaustivo, e o caso esquecido vira resposta nenhuma.

### Decisões de design do PR 2

- **`VaultBridge` sem Bukkit, `DreamEconomy` casca fina.** A lógica (arredondamento, cache ×
  banco, mapeamento de `Outcome` para `EconomyResponse`) é testada contra SQLite real; o adaptador
  só traduz jogador/nome para UUID e garante a main thread.
- **Conta offline: a main thread espera o banco.** A API do Vault é síncrona e quem chama precisa
  da resposta (a loja entrega o item ou não). A escrita passa pelo mesmo `submitOffline` do
  `applyOffline` — reserva, lápide e órfãos valem igual — e o que se espera é o futuro **cru** da
  thread do banco: esperar o que completa na main thread travaria. A thread do banco nunca espera a
  main, então não há impasse. Timeout em `vault.offline-timeout-millis` (5 s).
- **Timeout do banco é falha verdadeira** (revisão do PR #2, I3). Toda operação bloqueante só roda
  se ganhar uma *claim* (`AtomicBoolean`); no timeout quem espera disputa a claim e, ganhando, a
  tarefa vira no-op — nada mudou, e a reserva de uma retirada é devolvida. A primeira versão
  respondia falha com a escrita ainda na fila: dois saques "falhos" com o banco preso levavam o
  dinheiro do mesmo jeito, e um depósito "falho" é duplicação com plugins que repagam na falha. Só
  sobra incerteza se a tarefa **já começou** — aí espera-se o resultado real (até 30 s; o MySQL
  tem `socketTimeout` de 20 s) e o log avisa se nem isso bastar.
- **Disjuntor do banco**: timeout **ou falha** de uma operação bloqueante o abre, e daí em diante
  operação bloqueante nova falha **na hora**. Ele só fecha quando uma sonda que **consulta o banco**
  (`Storage#ping`) dá certo; falhando, outra vem 5 s depois. Sem ele, um leilão pagando vinte
  vendedores offline num tick esperaria vinte timeouts e o watchdog do Paper (60 s) derrubaria o
  servidor. A primeira versão fechava quando a fila andava e só abria no timeout: numa queda a
  tarefa pegava a claim na hora e esperava o timeout de conexão, a sonda fechava logo depois, e
  cada chamada congelava a main thread de novo (re-revisão do PR #2). O `connectionTimeout` do
  MySQL é 3 s para a primeira espera de uma queda ser curta. "Resultado desconhecido" (tarefa
  começou e não terminou em 30 s) é `OutcomeUnknownException`, separado de "nada foi alterado".
- **Serviço fechado depois do shutdown** (I1): toda mutação devolve `UNAVAILABLE`, e o
  `DreamEconomy` recusa com o plugin desligado. Lojas guardam a referência do provedor no
  `onEnable` delas; depois de um disable/reload só deste plugin, a instância antiga aceitava saque
  em conta do cache com SUCCESS sem nada chegar ao banco.
- **Ruído de ponto flutuante não vira bronze** (I2): o valor do Vault é arredondado em 8 casas
  antes do floor/ceil — lojas calculam preço × quantidade em `double`, e `0.1 + 0.2` cobrava 31
  bronze. A fração real abaixo de 1 bronze continua no floor/ceil (0.005 segue valendo zero).
- **Chamada assíncrona de outro plugin vai para a main thread** (`callSyncMethod`) e é esperada.
  No timeout a tarefa é **cancelada**; se ela já começou, espera-se o resultado real. Responder
  falha com a tarefa ainda na fila seria o mesmo resultado incerto, sem necessidade.
- **`has` arredonda para cima, como saque**: "tem 0.011?" só com 2 bronze. Valor ≤ 0 é sempre
  verdadeiro (se a conta existe).
- **Mundo é ignorado e bancos não existem** (`NOT_IMPLEMENTED`). A unidade anunciada é a prata
  (`currencyNameSingular`), coerente com `fractionalDigits() = 2`.
- **`createPlayerAccount(String)` só cria para jogador online** — um nome sozinho não identifica a
  conta. A variante com `OfflinePlayer` cria pelo UUID, sem contar como login (`last_seen` 0), para
  não roubar um nome reaproveitado.
- **Provedor concorrente é aviso SEVERE no primeiro tick**, depois de todo plugin subir: o
  Essentials pode registrar a economia dele depois de nós.
- **Limites conhecidos (documentados, não corrigidos):**
  - Loja que habilita **antes** de nós e guarda o provedor fica com o do Essentials (ou nenhum): a
    ordem entre plugins que só dependem do Vault é arbitrária. Conferir cada loja no roteiro
    manual; se precisar, `loadbefore:` com elas.
  - Os comandos de economia do **EssentialsX** (`/eco`, `/pay`, `/balance`, `/baltop`) mexem na
    economia **interna** dele, não no Vault: com os dois ativos haveria duas moedas paralelas.
    Desligar esses comandos no Essentials (`disabled-commands`) ou a economia dele.
  - Variantes do Vault por **nome** (deprecated): online exato, senão o `last_seen` mais recente.
    Contas criadas por plugin (`last_seen` 0) com o mesmo nome empatam.
  - `createPlayerAccount(OfflinePlayer)` cria conta com o `starting-balance` para **qualquer**
    UUID, inclusive NPC/cidade de outro plugin — mesmo comportamento do Essentials.
  - `getBalance` assíncrono (TAB, scoreboards) espera um tick pelo `callSyncMethod`. Um espelho
    somente-leitura dos saldos do cache para leitura fora da main thread entra no PR 5, junto com
    as placeholders, que têm o mesmo problema.

### Decisões de design do PR 1

- **O cache é a fonte das leituras e das chamadas síncronas do Vault; o banco recebe deltas.** O
  Vault é síncrono — consultar o banco a cada `withdrawPlayer` travaria a main thread.
- **Números de sequência no lugar de lock.** Uma thread só no banco (`SequencedExecutor`) torna a
  ordem de submissão a ordem de execução. O `AccountLedger` usa isso para decidir se uma leitura já
  incluiu uma escrita: pagamento offline que termina depois da carga é somado ao cache; antes, não
  (seria contar duas vezes). Resync: `cache = banco + flushes submetidos depois da leitura +
  pendente`.
- **Órfãos cobrem a janela pré-login → join.** O pré-login lê o banco numa thread assíncrona e a
  conta só entra no cache no join; um pagamento gravado nesse meio não seria visto por nenhum dos
  dois. A escrita fica guardada como órfã por até 5 min e é reaplicada no join se a leitura foi
  anterior a ela.
- **Uma conta só sai do cache quando não deve nada ao banco** (revisão do PR #1). Na saída ela fica
  offline no cache até o último flush confirmar; flush falho volta para a **mesma** entrada, e quem
  reconecta antes reaproveita a entrada. A primeira versão tirava a conta na hora e guardava o delta
  falho à parte (`detached`): a sessão nova, lida do banco, não o enxergava e o jogador gastava duas
  vezes — reproduzido com queda de banco + relog **e** com reconexão rápida sem falha nenhuma.
- **Lápide** com a sequência da última escrita da sessão que saiu: leitura de pré-login mais antiga
  que ela é recusada e a conta é relida.
- **Retirada offline é reserva pessimista, descontada inteira e sem piso**: um login no meio dela
  já entra descontado, e a recusa devolve o valor cheio. O saldo interno pode ficar negativo
  enquanto a reserva aguarda (negativo já recusa retirada); só `AccountLedger#balance` corta no
  zero, para exibição. Cortar o desconto no zero criava dinheiro: com um crédito anterior ainda a
  caminho, a parte cortada nunca era descontada quando a retirada confirmava (seed 832 do fuzz da
  re-revisão). Crédito offline continua otimista. Consequências travadas por teste: **`set` devolve
  `BUSY`** enquanto houver reserva descontada (o delta sobre o saldo descontado virava dinheiro se a
  retirada fosse recusada), o **teto** de depósito/transferência é checado contra o saldo no pior
  caso (reservas recusadas), e `applyOffline` recusa `|delta| > max` antes de virar reserva.
  **O `/moedas set` do PR 3 precisa tratar `BUSY`** com "tente de novo em instantes".
- **No máximo um flush em voo por conta** (re-revisão do PR #1). Com dois, um crédito que falhasse
  deixava o débito seguinte bater num banco sem o crédito: o piso cortava o débito, o crédito era
  regravado e o jogador ficava com dinheiro que gastou. O pendente de uma conta com flush em voo
  espera o resultado dele; só o desligamento pega tudo (`takeAllPending`), depois de esvaziar a fila.
- **Conta no cache sempre vai pelo cache**, inclusive em `applyOffline`.
- **O desligamento recupera flush em voo que falhou**: no `onDisable` a main thread já não roda o
  callback de falha, então o serviço guarda os lotes em voo e os relê direto do futuro.
- **O piso em zero do flush não é mais silencioso**: `applyDeltas` devolve o que não pôde debitar e
  o serviço loga em SEVERE. Com as regras acima, num servidor único ele não deveria agir; em rede,
  é a janela de troca de servidor abaixo. Se aparecer em servidor único, é defeito a investigar.
- **Fuzz de interleavings** (feito pelo revisor, fora do repositório): operações aleatórias contra
  SQLite real com a main thread manual — depósito, retirada, `applyOffline` ±, flush, falha
  simulada, resync, quit, reconexão com pré-login bloqueado e login duplicado — checando cache ==
  banco com tudo assentado e banco == soma das mutações aceitas no fim. 0 falhas em 5000 seeds.
  Vale portar para `src/test` quando o `/pagar` entrar.
- **Janela conhecida (MySQL em rede):** ao trocar de servidor, o pré-login do servidor B pode ler
  antes de o flush do servidor A confirmar — as sequências são por processo. O resync (padrão 30 s)
  corrige a exibição, e o piso + log acima registra se algo foi gasto duas vezes nesse meio.
- **`last_seen`** (gravado só no login) decide de quem é um nome reaproveitado na busca offline.
- **Conta carregada no pré-login, e falha recusa o login.** Entrar com saldo desconhecido daria
  "conta não carregada" em toda mutação, sem explicação para o jogador.
- **`onMain` em vez de `thenApplyAsync(fn, main)`.** O `thenApplyAsync` completa a *falha* na
  thread que falhou (a do banco), e o callback do comando rodaria fora da main thread justamente no
  caminho de erro.
- **Flush com guarda e piso explícito:** `UPDATE … WHERE balance + ? >= 0`; o que a guarda recusa
  é zerado à parte e devolvido como "não aplicado" (ver o log SEVERE acima). A escrita offline usa
  guarda completa (`WHERE balance + ? >= 0 AND balance + ? <= max`) e devolve o motivo da recusa.
- **`set` vira delta** em relação ao que o cache enxerga, para não apagar escrita de outro servidor
  ainda não sincronizada.
- **Busca por nome usa a comparação do dialeto** (`COLLATE NOCASE` / collation do MySQL) para o
  índice valer; `LOWER(name)` o ignoraria.
- **Teto de saldo 999.999.999.999.999 bronze.** O primeiro teto (9×10^15, "abaixo de 2^53") estava
  errado: o Vault recebe `bronze / 100`, e `8_999_999_999_999_999 / 100` volta do `double` como
  `…998`. Só decimais de até 15 dígitos significativos voltam idênticos.
- **Prova de regressão feita:** conversão em ponto flutuante, escrita externa contada duas vezes,
  órfão não reaplicado, conta saindo do cache devendo, ausência de lápide, ausência de reserva,
  reserva devolvida cheia e o teto antigo foram reconstruídos e cada um derruba a suíte.
