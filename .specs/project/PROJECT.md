# dreamCoinSystem — Projeto

## Visão

Economia do servidor Dream com três moedas simultâneas (ouro, prata, bronze) que se convertem
automaticamente, servida a todo o servidor pelo Vault e exposta ao chat/scoreboard/menus pelo
PlaceholderAPI.

## Objetivos

1. Ser o provedor de Economy do Vault — nenhum plugin de loja/leilão precisa saber que existem três
   moedas.
2. Pagamentos entre jogadores com várias moedas numa linha (`/pagar whitefox 1 ouro 35 bronze`).
3. Placeholders de saldo total e por moeda.
4. Zero perda ou duplicação de dinheiro: restart, queda de banco ou dois servidores no mesmo MySQL
   não podem criar nem sumir com saldo.

## Fora de escopo (v1)

Ranking, log de transações, moedas físicas (itens), bancos do Vault, múltiplas moedas
independentes (as três são uma só quantia).

## Decisões (2026-10-13, com o Murilo)

| Decisão | Motivo |
|---|---|
| Saldo = um `long` em bronze | Conversão automática por construção; aritmética inteira exata. |
| Vault 1.0 = 1 prata, `fractionalDigits = 2` | Bronze vira o "centavo"; preços pequenos nos configs. |
| Depósito arredonda para baixo, saque para cima | Fração de bronze nunca cria nem deixa escapar dinheiro. |
| Taxas fixas em código | Taxa configurável reescalaria saldos após ir ao ar. |
| SQLite ou MySQL configurável | Padrão do `ausAchievements`; MySQL se o Dream virar rede. |
| Gravação por delta atômico | Dois servidores no mesmo banco somam em vez de sobrescrever. |
| Cache dos online na main thread | O Vault é síncrono; banco por chamada travaria a main thread. |
| Resync periódico só no MySQL | Pagamento feito em outro servidor aparece sem relogar. |
| Só três moedas, "cobre" não é alias | Confirmado pelo Murilo. |

## Stack

Java 25 · Paper 1.21.11 · Maven · VaultAPI 1.7.1 · PlaceholderAPI 2.12.2 · SQLite 3.53.2.1 /
MySQL Connector 9.7.0 · HikariCP 7.1.0 · JUnit 5.14.4.

## Superfície de entrada de dados

- Argumentos de `/pagar`, `/saldo`, `/moedas` (texto livre do jogador) → `MoneyParser` com erro
  tipado; nada chega ao SQL sem `PreparedStatement`.
- Nomes de jogador usados para lookup offline → parâmetro de `PreparedStatement`, nunca concatenado.
- Valores `double` vindos de outros plugins pelo Vault → `VaultAmounts` recusa negativo/NaN/infinito.
- Credenciais do MySQL ficam no `config.yml` do servidor; nunca versionar o config de produção.
