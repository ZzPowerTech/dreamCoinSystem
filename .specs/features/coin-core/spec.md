# Feature: coin-core — economia de três moedas (v1)

## Requisitos

### Moedas (COIN)
- **COIN-01** Três moedas: ouro = 100.000 bronze, prata = 100 bronze, bronze = 1. Taxas fixas.
- **COIN-02** Saldo é um inteiro não negativo em bronze; ouro/prata/bronze são a decomposição
  (prata 0–999, bronze 0–99 na exibição).
- **COIN-03** Aritmética com overflow detectado (erro, nunca saldo negativo ou "dando a volta").
- **COIN-04** Parser aceita `<n> <moeda>` e `<n><alias>` misturados, qualquer ordem. Aliases:
  `ouro|ouros|o`, `prata|pratas|p`, `bronze|bronzes|b`, sem diferenciar maiúsculas.
- **COIN-05** Parser recusa, com erro específico: moeda desconhecida (incl. `cobre`), moeda
  repetida, valor ≤ 0, não inteiro, número sem moeda, nada informado, overflow.
- **COIN-06** Formatação longa `1 ouro, 2 prata e 3 bronze` (omite zeros; zero → `0 bronze`),
  curta `1o 2p 3b`, separador de milhar pt-BR; nomes e formatos no `messages.yml`.

### Persistência (STO)
- **STO-01** SQLite (padrão) ou MySQL por `storage.type`.
- **STO-02** Tabela `dc_accounts(uuid, name, balance, updated_at)`; criada em todo boot se faltar.
- **STO-03** Toda alteração de saldo é gravada como delta atômico com guarda de não-negativo.
- **STO-04** Conta criada no primeiro login com `starting-balance`; nome atualizado a cada login.
- **STO-05** Deltas pendentes gravados no quit, a cada `flush-interval-seconds` e no desligamento
  (que espera a fila esvaziar). MySQL: resync dos online a cada `resync-interval-seconds`.

### Vault (VLT)
- **VLT-01** Registrado como `Economy` com `ServicePriority.Highest`.
- **VLT-02** 1.0 = 1 prata; `fractionalDigits() = 2`; `format()` usa a formatação longa.
- **VLT-03** Depósito arredonda para baixo; saque e `has` para cima; negativo/NaN/infinito → falha.
- **VLT-04** Bancos não suportados (`NOT_IMPLEMENTED`).
- **VLT-05** Aviso no console se outro provedor estiver ativo no lugar.

### Comandos (CMD / ADM / PAY)
- **CMD-01** `/saldo [jogador]` — próprio saldo (`dreamcoins.balance`) ou de outro
  (`dreamcoins.balance.others`).
- **ADM-01..03** `/moedas give|take|set <jogador> <valor...>` (`dreamcoins.admin`), online ou
  offline com conta. `take` acima do saldo é recusado (não zera em silêncio).
- **ADM-04** `/moedas reload` relê config e mensagens.
- **PAY-01** `/pagar <jogador> <valor...>` debita e credita na mesma operação de main thread.
- **PAY-02** Recusa pagar a si mesmo.
- **PAY-03** Recusa alvo sem conta; offline só se `allow-offline`.
- **PAY-04** Recusa abaixo de `min-amount`; acima de `max-amount` (se > 0).
- **PAY-05** Cooldown `cooldown-seconds` por pagador (bypass `dreamcoins.bypass.cooldown`).
- **PAY-06** Acima de `confirm-above` exige `/pagar confirmar` em até `confirm-timeout-seconds`
  (bypass `dreamcoins.bypass.confirm`).
- **PAY-07** Recusa saldo insuficiente e crédito que estouraria `max-balance` do recebedor.
- **PAY-08** Mensagem ao pagador e ao recebedor (se online) com valor e novo saldo.
- **PAY-09** Tab-complete de jogadores, números e moedas.

### Placeholders (PH)
- **PH-01** Identificador `dreamcoins`, lê só cache (nunca banco).
- **PH-02** `balance`, `balance_short`, `gold|silver|bronze`, `total_gold|silver|bronze`,
  `total_<moeda>_formatted`, `balance_vault`, `highest`.
- **PH-03** Jogador sem conta carregada → `0`/`0 bronze`, nunca vazio ou erro.

## Critérios de aceite (exemplos do pedido)

Com saldo do pagador de 2 ouro:

| Comando | Debitado (bronze) | Saldo do pagador depois |
|---|---|---|
| `/pagar whitefox 2 prata 1 bronze` | 201 | 1 ouro, 997 prata e 99 bronze |
| `/pagar whitefox 1 ouro 35 bronze` | 100.035 | 999 prata e 65 bronze |
| `/pagar whitefoxone 1 ouro` | 100.000 | 1 ouro |
| `/pagar whitefoxone 1 ouro 2 prata 3 bronze` | 100.203 | 997 prata e 97 bronze |
| `/pagar whitefoxone 2 bronze` | 2 | 1 ouro, 999 prata e 98 bronze |
| `/pagar whitefox 3 cobre` | — | erro "moeda desconhecida: cobre" |
