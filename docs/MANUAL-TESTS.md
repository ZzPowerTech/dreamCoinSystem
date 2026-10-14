# Roteiro de teste manual

Servidor local Paper 1.21.11 com o `dreamCoinSystem.jar` em `plugins/`. Cada seção corresponde a um
PR; os itens marcados **⚠** são os que nenhum teste automático alcança.

## PR 1 — núcleo, banco e `/saldo`

Preparação: apague `plugins/dreamCoinSystem/` para começar do zero.

1. **Boot limpo.** Suba o servidor. Esperado: `Economia pronta (sqlite).` no console, sem aviso;
   `plugins/dreamCoinSystem/` com `config.yml`, `messages.yml` e `dreamcoins.db`.
2. **Conta criada no login.** Entre com um jogador. `/saldo` → `Seu saldo: 0 bronze`.
3. **Saldo inicial.** Pare o servidor, mude `accounts.starting-balance: "1 ouro 50 prata"`, apague o
   `.db`, suba e entre. `/saldo` → `1 ouro e 50 prata`.
4. **Saldo de outro.** Como OP: `/saldo <outro>` online e offline (que já entrou alguma vez) →
   `Saldo de <nome>: ...`. `/saldo ninguem123` → `Jogador ninguem123 não encontrado.`
5. **Permissão.** Sem OP, `/saldo <outro>` → sem permissão; `/saldo <seu próprio nome>` funciona.
6. **⚠ Persistência entre restarts.** Com saldo ≠ 0 (passo 3), reinicie. `/saldo` igual.
7. **⚠ Reload com jogador online.** `/reload confirm` (ou PlugMan). `/saldo` volta a responder em
   segundos (a conta é recarregada em segundo plano; antes disso aparece "conta carregando").
8. **⚠ Config inválido.** `storage.type: postgres` e `starting-balance: "10 cobre"` → dois avisos
   nomeando as chaves; o plugin sobe com sqlite e saldo inicial 0.
9. **⚠ Banco indisponível (MySQL).** `storage.type: mysql` apontando para host inexistente → erro
   no console e plugin desligado, sem stack trace em loop.
10. **⚠ MySQL compartilhado.** Dois servidores no mesmo banco, `resync-interval-seconds: 10`. Edite
    o saldo direto no banco (`UPDATE dc_accounts SET balance = balance + 100 WHERE name = '...'`)
    com o jogador online num deles: em até 10 s o `/saldo` mostra +1 prata.

Mudar saldo ainda exige o banco nesta fase — `/moedas give` chega no PR 3.
