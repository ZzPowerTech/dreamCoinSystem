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
6. **Saldo nunca negativo** e com teto (`max-balance`, padrão 9×10^15 bronze) para o `double` do
   Vault continuar exato.

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

Bootstrap: spec e roadmap escritos; PR 1 (`feature/core-storage`) é o próximo.
