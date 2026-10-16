# Roteiro de teste manual

Servidor local Paper 1.21.11 com o `dreamCoinSystem.jar` em `plugins/`. Cada seção corresponde a um
PR; os itens marcados **⚠** são os que nenhum teste automático alcança.

## PR 1 — núcleo, banco e `/saldo`

Preparação: apague `plugins/dreamCoinSystem/` para começar do zero. **Obrigatório** se você testou
uma build anterior às correções da revisão: o schema ganhou a coluna `last_seen` sem migração (o
plugin ainda não foi ao ar), e um `.db` antigo faz todo login ser recusado.

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

## PR 2 — provedor do Vault

Preparação: Vault + Essentials (com a economia dele) + uma loja que use Vault (ShopGUIPlus ou
similar) no servidor local.

1. **Provedor ativo.** Suba o servidor. `/vault-info` mostra `Economy: dreamCoinSystem`. Sem aviso
   SEVERE de "outro provedor de economia".
2. **⚠ Concorrente.** Remova temporariamente o `depend`/prioridade (ou instale um plugin que
   registre economia com `Highest`): o console mostra o SEVERE nomeando o outro plugin.
3. **Compra.** Com saldo de 20 prata (`UPDATE dc_accounts SET balance = 2000 …` com o servidor
   parado, até o PR 3 trazer `/moedas give`), compre um item de preço `12.50`. `/saldo` →
   `7 prata e 50 bronze`.
4. **Venda.** Venda um item de preço `0.015`: entra 1 bronze (depósito arredonda para baixo).
5. **Saldo insuficiente.** Tente comprar algo acima do saldo: a loja recusa e o saldo não muda.
6. **⚠ Essentials.** Os comandos de economia do EssentialsX (`/eco`, `/pay`, `/balance`) usam a
   economia **interna** dele, não o Vault. Confirme que estão em `disabled-commands` no
   `config.yml` do Essentials; senão haverá duas moedas paralelas.
7. **⚠ Jogador offline.** Com o jogador desconectado, pague-o por um plugin que use o Vault
   (ex.: venda no leilão, comando do plugin de loja): o banco recebe +200
   bronze em até alguns ms, sem travar o servidor; ao entrar, `/saldo` mostra o valor.
8. **⚠ Formatação.** Mensagens de lojas que usam `Economy#format` mostram `12 prata e 50 bronze`.
9. **⚠ Cada loja usa o dreamCoinSystem.** Para cada plugin de loja/leilão instalado, faça uma
   compra e confira o `/saldo`. Loja que habilitou antes de nós pode ter guardado outro provedor.
10. **⚠ Banco travado.** Com MySQL, derrube o banco e faça uma venda para um jogador offline: a
    loja recebe falha em ~5 s, o console avisa "abandonada sem efeito", e vendas seguintes falham
    **na hora** (disjuntor) sem travar o servidor. Religue o banco: tudo volta a funcionar.

## PR 3 — `/moedas`

1. **Permissão.** Sem OP, `/moedas give <você> 1 ouro` → sem permissão.
2. **give/take online.** `/moedas give <jogador> 1 ouro 35 bronze` → mensagem com o saldo novo; o
   jogador recebe o aviso; o console registra `[moedas] ... give 100035 bronze -> ...`.
   `/moedas take <jogador> 999 ouro` acima do saldo → "tem só ...", saldo intacto.
3. **set.** `/moedas set <jogador> 0` zera; `/moedas set <jogador> 5 prata` → `5 prata`.
4. **Offline.** Com o jogador desconectado: give, take e set funcionam e o `/saldo <nome>` mostra o
   valor; ao entrar, o saldo confere.
5. **⚠ set com drift.** Com o jogador offline, rode `/moedas set <nome> 0` e, no mesmo instante,
   pague-o por uma loja (Vault): se a venda cair no meio, o admin vê "o saldo mudou durante a
   operação" com o valor real.
6. **Erros de quantia.** `/moedas give x 3 cobre` → "Moeda desconhecida: cobre";
   `/moedas give x 0` → "maior que zero"; `/moedas give x 1.5 ouro` → "números inteiros".
7. **reload.** Mude `payments`/mensagens e `accounts.starting-balance`: `/moedas reload` aplica.
   Mude `storage.type`: o reload avisa que precisa reiniciar.

## PR 4 — `/pagar`

Dois jogadores (A e B), A com 2 ouro.

1. **Exemplos do pedido**, com B online e depois offline:
   `/pagar B 2 prata 1 bronze` · `/pagar B 1 ouro 35 bronze` · `/pagar B 1 ouro` ·
   `/pagar B 1 ouro 2 prata 3 bronze` · `/pagar B 2 bronze`. Conferir o saldo de A na mensagem
   (ex.: depois de `1 ouro 35 bronze` sobra `999 prata e 65 bronze`) e o `/saldo` de B.
2. **Forma compacta e mistura.** `/pagar B 2p 1b`, `/pagar B 1o 35 bronze`.
3. **Erros.** `/pagar B 3 cobre` → moeda desconhecida; `/pagar <A> 1 ouro` → não pode pagar a si
   mesmo; acima do saldo → "Saldo insuficiente. Você tem …".
4. **Confirmação.** `/pagar B 11 ouro` (com saldo) → pede `/pagar confirmar`; sem confirmar nada é
   debitado; confirmar depois de 30 s → "não tem pagamento esperando".
5. **Cooldown.** Dois pagamentos em menos de 3 s → "Espere N s".
6. **Bypass.** Com `dreamcoins.bypass.cooldown` e `dreamcoins.bypass.confirm`, nenhuma das duas
   travas aparece.
7. **⚠ Offline com falha.** Com MySQL, derrube o banco e pague B offline: A recebe erro de banco e o
   saldo de A volta (a transação não foi aplicada); religue o banco e confira os dois saldos.
8. **⚠ Recebedor no teto.** Reduza `accounts.max-balance` abaixo do saldo de B e `/moedas reload`:
   pagar B → "não pode receber tanto"; A não perde nada.
9. **Console.** Cada pagamento concluído aparece como `[pagar] A -> B (...): N bronze`.
