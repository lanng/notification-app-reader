# Monitor de saldo de tags

Vocabulário do acompanhamento pessoal de saldo por avisos bancários. A estimativa
é auxiliar e não equivale a uma consulta ao saldo oficial.

## Linguagem

**Aviso bancário**:
Mensagem de notificação emitida pelo banco, que pode descrever uma movimentação
ou apenas uma comunicação comercial.
_Evitar_: Lançamento confirmado, movimentação garantida.

**Observação**:
Registro de que um aviso foi recebido, atualizado, encontrado novamente ou removido.
Várias observações podem se referir à mesma movimentação.
_Evitar_: Transação, débito, crédito como sinônimos de observação.

**Movimentação**:
Crédito ou débito reconhecido e aceito no acompanhamento, com impacto único
na estimativa.
_Evitar_: Notificação, callback, observação como sinônimos de movimentação.

**Saldo de referência**:
Valor atual consultado no banco e informado pelo responsável como ponto de
partida ou correção do acompanhamento.
_Evitar_: Recarga manual, receita, saldo consultado automaticamente.

**Marco de conferência**:
Ponto a partir do qual vale um saldo de referência; o que já está incluído nesse
valor não deve ser reaplicado.
_Evitar_: Data de uso da tag, início retroativo.

**Saldo estimado**:
Saldo de referência acrescido dos créditos e reduzido dos débitos aceitos após
o marco de conferência.
_Evitar_: Saldo oficial, saldo garantido, saldo bancário consultado.

**Correção de saldo**:
Substituição da estimativa por um novo saldo de referência, preservando o
histórico anterior.
_Evitar_: Depósito, Pix, receita, recarga.

**Pendência**:
Possível movimentação cuja interpretação ou identidade requer conferência antes
de alterar a estimativa.
_Evitar_: Débito confirmado, movimentação aplicada.

**Limite de atenção**:
Valor configurado abaixo do qual a estimativa demanda aviso ao responsável.
_Evitar_: Limite de crédito, limite bancário.

**Ciclo de alerta**:
Episódio de saldo abaixo do limite, com seus avisos e eventuais pausas, até a
recuperação reconhecida do saldo.
_Evitar_: Uma notificação isolada, uma tentativa de aviso.

**Ciência**:
Interação explícita do responsável principal para pausar temporariamente apenas
seus lembretes do ciclo atual; não significa que houve recarga.
_Evitar_: Problema resolvido, saldo recuperado, leitura garantida.

**Pausa**:
Período temporário sem novos lembretes para o responsável que deu ciência,
sem encerrar o ciclo de alerta nem pausar os responsáveis de apoio.
_Evitar_: Encerramento, recuperação.

**Recuperação do saldo**:
Retorno do saldo conferido ou estimado sem pendências relevantes ao limite de
atenção ou acima dele.
_Evitar_: Qualquer crédito recebido, ciência.

**Responsável principal**:
Pessoa cujo aparelho acompanha os avisos bancários e recebe o primeiro alerta.
_Evitar_: Conta compartilhada, acesso bancário dos responsáveis de apoio.

**Responsável de apoio**:
Pessoa autorizada a consultar a estimativa compartilhada e receber escalonamento
de alertas, sem editar o acompanhamento nem acessar a conta bancária do principal.
_Evitar_: Titular adicional, destinatário já conectado nesta versão.

**Host**:
Aparelho principal ativo que acompanha os avisos bancários e origina a estimativa
de um monitor; não é a identidade permanente do proprietário.
_Evitar_: Servidor, conta Google, banco como sinônimos de host.

**Escalonamento**:
Extensão dos avisos de um nível aos responsáveis de apoio depois da precedência
do host; não exige comprovação de leitura do primeiro aviso.
_Evitar_: Encaminhamento bancário, leitura confirmada.

**Nível de atenção**:
Faixa em que a estimativa está abaixo do limite de atenção, mas não abaixo da
metade desse limite.
_Evitar_: Saldo normal, saldo oficial.

**Nível crítico**:
Faixa em que a estimativa está abaixo da metade do limite de atenção.
_Evitar_: Saldo negativo como única condição crítica.

**Agravamento**:
Entrada em um nível mais crítico, distinta de um lembrete do mesmo nível.
_Evitar_: Nova movimentação, nova recarga, repetição como sinônimos de agravamento.

**Parar som**:
Interrupção somente do toque do aviso atual, sem registrar ciência, encerrar
o ciclo ou cancelar os próximos lembretes.
_Evitar_: Resolver, confirmar recarga, pausar o acompanhamento.

**Último saldo conhecido**:
Estimativa anteriormente recebida do host, acompanhada de sua atualização;
não representa confirmação do saldo no momento da consulta.
_Evitar_: Saldo atual garantido, saldo bancário consultado.

**Sem contato recente com o host**:
Ausência de confirmação recente de comunicação com o host, diferente de ausência
de movimentações e sem comprovar por si só que o aparelho esteja desligado.
_Evitar_: Conta sem movimentação, saldo recuperado, desconexão comprovada.
