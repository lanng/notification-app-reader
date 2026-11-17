# Monitor local C6 Tag — desenho

**Estado:** regras, propostas visuais e documento aprovados pelo usuário; revisão
independente aprovada. Planejamento de implementação autorizado.

## 1. Objetivo e escopo

Evoluir o coletor instalado para um aplicativo Android de acompanhamento diário:
saldo estimado, histórico financeiro e avisos locais de saldo baixo. Reaproveitar
Kotlin, Compose, captura, classificação e diagnóstico existentes.

O aplicativo não consulta a conta, não acessa credenciais e não realiza recargas.
O saldo sempre será apresentado como estimado.

### Incluído

- Saldo de referência informado e correções manuais.
- Reconhecimento conservador e aplicação única de créditos Pix e débitos C6 Tag.
- Histórico financeiro separado das observações técnicas.
- Pendências sem aplicação automática e indicação de saldo a conferir.
- Limite configurável, primeiro aviso local, lembretes e ciência temporária.
- Temas claro, escuro e acompanhar sistema.
- Diagnóstico existente acessível em Ajustes.
- Migração sem apagar observações nem exigir reinstalação.

### Fora do escopo

- Outros aparelhos, contas de usuário, autenticação, pareamento, push ou servidor.
- Escalonamento a responsáveis de apoio, registrado para a próxima etapa.
- API bancária, login automatizado, OCR, leitura de tela ou extratos.
- Recargas automáticas, controle por veículo, previsão de consumo ou gráficos.
- Alarme exato, som garantido ou contorno das preferências do Android.
- Importação financeira automática do histórico anterior à configuração.

Nome visível de trabalho: **Monitor de tags**, sem sugerir vínculo oficial com o
banco. Manter a identidade de instalação atual para preservar dados e autorização.

## 2. Evidência e limites

O S23 foi identificado por consultas autorizadas: SM-S911B, Android 17/API 37.
O pacote `com.c6bank.app` foi identificado e confirmado pelo usuário. O diagnóstico
foi instalado com autorização; o acesso especial foi orientado para concessão manual.

O usuário conferiu 8 avisos de tags e 2 propagandas sem valor, e confirmou um Pix
voluntário de R$ 1,00 reconhecido com atualização da lista. Depois relatou que o
diagnóstico funcionou corretamente.

A base existente passou por revisões de escopo/qualidade e revisão final,
32 testes JVM, 47 instrumentados no emulador e lint sem erros. Isso não comprova
entrega integral, identidade bancária de transações ou confiabilidade contínua.

Repetições reais, reconexões, processo recriado, bloqueio de tela e atrasos do
fabricante precisam de nova validação, sem exigir transações.

Vocabulário: `CONTEXT.md`. Evidências: `docs/testing/verification-results.md`.

## 3. Direção visual aprovada

Layout A: limpo, poucos elementos, saldo destacado e navegação inferior:
**Visão geral**, **Histórico**, **Ajustes**.

### Temas

| Token | Claro | Escuro |
|---|---|---|
| Fundo | `#f8f7fc` | `#0d0b10` |
| Superfície | `#ffffff` | `#17141d` |
| Texto principal | `#292232` | `#f2eef8` |
| Texto secundário | `#70677e` | `#b0a6bd` |
| Destaque | `#6c42ae` | `#c6a6f2` |

Claro em branco/lilás; escuro em preto/grafite/roxo. Atenção em âmbar, para
diferenciar alerta da identidade. Não usar cor como único indicador de estado.

Preferências locais: acompanhar sistema por padrão, claro ou escuro explícito.
Mesma organização nos dois temas, sem depender de login ou acesso à internet.

Usar tipografia e medidas apropriadas ao Android, não copiar os tamanhos pequenos
dos mockups de navegador. Alvos de toque de pelo menos 48 dp, texto escalável,
TalkBack e contraste dos estados reais devem ser testados.

Referências visuais auxiliares, não necessárias para interpretar este documento:

- `.superpowers/brainstorm/109706-1794883145/visao-geral-roxo-v2.html`
- `.superpowers/brainstorm/109706-1794883145/fluxos-configuracao-historico-ajustes.html`

## 4. Telas e fluxos

### Configuração inicial

1. Explicar que não há acesso ao saldo nem necessidade de senha.
2. Conferir o acesso especial à captura, sem concedê-lo automaticamente.
3. Solicitar saldo atual consultado no C6 e limite de atenção, sugerindo R$ 300,00.
4. Explicar que o valor informado já inclui o passado e não é uma recarga.
5. Confirmar o marco de conferência e persistir a configuração.
6. Explicar e solicitar, em contexto, permissão para os avisos do próprio app.

Recusar avisos não bloqueia consulta ao histórico ou saldo, mas a interface
deve deixar claro que o usuário não pode contar com notificações externas.
Sem saldo de referência, apenas o diagnóstico funciona: avisos antigos não são
transformados em receitas/despesas retroativas.

### Visão geral

- Saldo estimado, limite e última captura observada.
- Estado do acompanhamento separado da autorização de captura e dos avisos.
- Últimas movimentações e ação de conferir/ajustar saldo.
- Saldo normal, saldo baixo, pausa por ciência e saldo a conferir.
- Ciência e retomada manual dos lembretes quando aplicáveis.

Não usar “saldo seguro”, “captura completa” ou “monitoramento garantido”.
Quando a continuidade não puder ser confirmada, mostrar a última estimativa
como valor a conferir, não afirmar que o banco ou o Android comprovadamente falhou.

### Histórico

- Movimentações aceitas e correções, com filtros Todos/Entradas/Saídas/Ajustes.
- Paginação por identidade local, sem carregar todo o histórico.
- Pendências destacadas separadamente; elas ainda não afetam o saldo.
- Ordenação pelo recebimento/conferência, nunca pela data antiga de uso da tag.
- Detalhe com valor, origem e evidência necessária; acesso ao diagnóstico quando útil.

Correção mostra o novo saldo de referência e não é tratada como receita.
Propagandas, resumos de grupo e remoções não são movimentações.

### Ajustes

- Conferir/corrigir saldo e alterar o limite.
- Repetição e pausa por ciência.
- Som e permissões: abrir preferências nativas de avisos/canal quando necessário.
- Aparência e diagnóstico técnico.
- Exportação e limpeza do diagnóstico preservadas, com seus avisos de privacidade.

Novas observações podem fazer o histórico voltar à primeira página, mantendo os
dados anteriores acessíveis ao paginar. Não confundir essa política com perda de dados.

## 5. Saldo de referência e marco

O saldo informado substitui a estimativa atual. Cada configuração/correção cria
um marco persistente; o histórico anterior permanece consultável.

O marco deve incluir um instante e uma fronteira serial de observações já recebidas,
não apenas a data de uso contida no texto. Notificações anteriores, resumos e
fotografias iniciais não são reaplicados ao novo saldo.

Se novos avisos chegarem durante a conferência entre abrir o formulário e confirmar
o saldo, não presumir que o número informado já os inclui. Sinalizar o caso e pedir
conferência; não aplicar nem ignorar silenciosamente movimentos concorrentes.
Esses avisos potencialmente financeiros devem permanecer identificáveis e pendentes
após criar o marco, mesmo que seus IDs estejam dentro da fronteira serial.
Só uma inclusão explicitamente confirmada no saldo ou resolução individual os cobre;
o corte por ID não substitui essa confirmação.

Uma nova correção pode absorver pendências anteriores até a fronteira confirmada,
registrando que foram incluídas no acerto, sem gerar crédito/débito adicional.
Decisões sobre pendências de um marco antigo não podem alterar um marco novo.

Todas as quantias são centavos inteiros, sem ponto flutuante. O saldo pode ficar
negativo; não limitá-lo artificialmente a zero. Overflow e entrada inválida são
falhas explícitas, nunca arredondamento silencioso.

## 6. Observações e movimentações

O coletor continua preservando observações; o monitor decide se uma observação
é elegível para criar uma movimentação única.

### Não aplicar automaticamente

- Observações anteriores à configuração ou cobertas pelo marco confirmado.
- Resumo de grupo, remoção, lifecycle ou snapshot de reconexão.
- Propaganda e formatos não reconhecidos como movimentação.
- Evento com valor inválido, origem incorreta ou identidade insuficiente.
- Caso classificado como pendente.

Título financeiro conhecido com valor ilegível deve gerar pendência; uma propaganda
não se transforma em pendência financeira apenas por ser uma notificação do C6.
Formatos desconhecidos permanecem no diagnóstico; mudanças do banco podem exigir
atualização do reconhecedor e reconciliação manual.

### Política conservadora de identidade

Metadados Android não são identificadores oficiais de transação bancária.
Aplicar regras explícitas e auditáveis, sem deduplicar por valor isolado:

1. Mesma chave de notificação e horário de postagem, com mesmo tipo e valor:
   tratar como atualização/repetição da publicação já observada, sem novo impacto.
2. Mesma publicação com tipo/valor conflitante: pendência, não corrigir um
   lançamento silenciosamente.
3. Reutilização da mesma chave com novo horário de postagem: pendência, pois pode
   ser nova cobrança ou reapresentação.
4. Chave nova com conteúdo financeiro integral idêntico ao de outra publicação:
   pendência, não assumir nem duplicidade nem nova cobrança.
5. Chave nova e candidato válido, após o marco e sem indícios conhecidos de
   reapresentação: elegível para aplicação única, mesmo que tenha o mesmo valor
   de outra cobrança com evidência diferente.

A normalização não pode remover números, datas e outros elementos que distingam
cobranças. Regras e casos de enriquecimento de texto devem ter fixtures explícitas.
Ausência de chave/horário, fronteira temporal duvidosa ou alteração conflitante
precisam de conferência, não heurística otimista.

Essa política reduz riscos conhecidos, sem prometer reconhecer todas as duplicações.
Uma cobrança com uso de vários dias antes pode ser nova: a data de uso não é filtro
para descartar um débito recebido depois do marco.

### Resolução de pendências

Exibir a evidência, o motivo e se já existe movimentação relacionada.
Para candidato com tipo/valor legíveis, permitir confirmar como nova movimentação
ou indicar que já estava incluída/não deve contar. Toda decisão é persistida e
idempotente; tocar duas vezes não altera duas vezes.

Valor não legível ou contexto sem confiança deve direcionar à conferência/correção
do saldo, sem inventar valor. A resolução nunca exige enviar extrato ou credenciais.

## 7. Arquitetura e persistência

Manter um único aplicativo, Kotlin/Compose e processamento local.

- **Module Captura:** preserva o filtro exato do C6, observações e estado de conexão.
- **Module Acompanhamento:** concentra marco, reconhecimento financeiro,
  identidade, pendências, aplicação única, saldo e histórico.
- **Module Alertas:** concentra ciclo, ciência, pausa, elegibilidade de emissão
  e integração com as notificações e o agendamento Android.
- **Module Apresentação:** expõe os estados reais e as ações das telas, sem
  executar regras financeiras nem operações de disco na thread principal.

Cada Interface deve esconder a implementação de persistência/captura/agendamento.
Não criar framework genérico nem novos aplicativos ou camadas de repasse.

Fluxo principal:

`aviso C6 → observação → decisão financeira → saldo persistido → avaliação do alerta → interface/aviso local`

SQLite continua sendo a base privada. Evoluir o esquema existente com migração
não destrutiva. Preservar o diagnóstico e não reinterpretar seus registros antigos
como lançamentos financeiros da nova instalação.

Aplicação de movimentação, decisão de identidade, saldo, fronteira de processamento
e estado financeiro relacionado devem ser consistentes em transação. Reexecução
ou recuperação depois de interrupção não pode repetir impacto financeiro.
Usar uma ordenação explícita para observações, correções, ciência, reavaliações
e alterações de configuração.

É necessário recuperar processamento pendente após recriação do processo, sem
depender apenas de um StateFlow ou de um booleano em memória.

Limpar diagnóstico não apaga saldo, histórico financeiro, decisões de identidade ou
pausa. Essas informações devem continuar consistentes mesmo quando o registro bruto
for removido. Não acrescentar botão de apagar todo o acompanhamento nesta entrega.

## 8. Continuidade e estado a conferir

Autorização de captura, conexão observada, continuidade do acompanhamento e
permissão de emitir avisos são informações distintas.

Revogação/desconexão, processo recriado sem continuidade comprovada, fronteira
ambígua ou pendência relevante devem sinalizar que o saldo precisa de conferência.
Reconectar ou encontrar notificações ativas não corrige automaticamente uma lacuna.

Continuar preservando novas observações. As decisões ambíguas ficam pendentes;
uma conferência explícita restabelece o marco. O indicador significa
“sem pendências conhecidas”, nunca auditoria completa do banco.

Se já houver um ciclo de saldo baixo e o acompanhamento estiver a conferir,
não encerrá-lo silenciosamente por uma recuperação não confiável. Manter a
necessidade de conferência até decisão válida ou novo saldo de referência.

## 9. Ciclo de alerta e ciência

Condição: saldo abaixo do limite; igualdade ao limite não é saldo baixo.
Primeira configuração ou alteração do limite também deve avaliar a condição.

| Situação | Resultado |
|---|---|
| Passa a ficar abaixo do limite | Abre ciclo e solicita primeiro aviso local |
| Novo débito enquanto já está baixo | Atualiza saldo; não cria novo ciclo nem reinicia o prazo repetidamente |
| Ciência do ciclo atual | Persiste pausa e interrompe os lembretes temporariamente |
| Retomada manual | Encerra a pausa atual e reavalia ciclo e prazo; não cria nem encerra ciclo |
| Pausa termina e saldo segue baixo | Lembretes elegíveis na próxima avaliação |
| Crédito ainda insuficiente | Não encerra ciclo; respeita pausa existente |
| Saldo conferido ou estimativa sem pendências conhecidas recupera limite ou acima | Encerra ciclo, cancela aviso e lembretes |
| Ação de ciência de ciclo antigo | Ignora; não pausa um ciclo novo |
| Notificação descartada sem usar ação de ciência | Não presume ciência |

Padrões aprovados: repetição prevista de 15 minutos, pausa de 30 minutos.
Controles devem oferecer valores compatíveis com o agendador, nunca prometer
intervalos periódicos menores que os suportados. Proposta inicial de escolhas:
repetição 15/30/60 minutos; pausa 15/30/60/120 minutos.

Alterações de configuração não devem produzir rajadas nem múltiplos ciclos.
Mudar a duração preferida da pausa não estende retroativamente uma pausa já
confirmada; a próxima ciência usa a nova preferência.
A retomada manual só emite se o ciclo ainda estiver ativo e o prazo de repetição
permitir; caso contrário, aguarda a próxima avaliação elegível. Não recria alerta
já resolvido nem aplica ação de um ciclo antigo.

O primeiro destinatário é somente o aparelho principal. Nenhum envio remoto
existe nesta entrega, mesmo sem ciência.

## 10. Emissão e limitações Android

Depois da atualização financeira confirmada, avaliar a necessidade de emissão
no próprio fluxo, sem esperar pelo agendamento periódico.

WorkManager reavalia estado persistido como mecanismo complementar. A cadência
mínima é 15 minutos e não é exata; Doze, Samsung e outras condições podem atrasar
a execução. Pausa e próximo lembrete devem ser apresentados como previstos.
Worker não consulta banco, não recupera notificações perdidas e não constitui
monitoramento independente da captura.

Não zerar o agendamento em todo débito. Usar trabalho único e estado persistente
para evitar multiplicação de jobs/avisos, cancelar quando não for mais necessário
e validar o ciclo novamente antes de emitir.

A intenção de emitir e o último resultado devem ser recuperáveis depois de
interrupção. Publicar notificação é efeito externo à transação: não prometer
exactly-once ou que foi vista/soou. Uma identidade estável de notificação e a
validação do ciclo reduzem duplicações e avisos obsoletos.

Criar canal próprio e solicitar POST_NOTIFICATIONS nas versões aplicáveis.
Recusa, canal bloqueado/silenciado e falta de acesso à captura devem aparecer
separadamente. O usuário escolhe som/importância nas preferências do Android;
o app não restaura silenciosamente configurações alteradas pelo usuário.

Ciência disponível no app e na notificação, com ação explícita e identidade do ciclo.
PendingIntent imutável e identidade que não dependa apenas de extras, para evitar
confusão entre ações antigas e novas.

Sem alarmes exatos, acesso para ignorar Não Perturbe, full-screen intent ou serviço
em primeiro plano permanente. Force-stop pode impedir captura e lembretes até
ação do usuário; não prometer execução nesse estado.

WorkManager 2.12.0 teve seu AAR oficial inspecionado: minCompileSdk 35,
minAndroidGradlePluginVersion 8.6.0 e minSdk 24, inferiores aos da base 36/9.0.1/26.
O plano deve confirmar também o grafo transitivo e o build completo, sem elevar
SDK ou dependências por suposição.

O manifesto da biblioteca inclui WAKE_LOCK, ACCESS_NETWORK_STATE,
RECEIVE_BOOT_COMPLETED e FOREGROUND_SERVICE, além de seus componentes de
agendamento. A presença de declaração da biblioteca não equivale a manter um
serviço em primeiro plano rodando. Auditar e documentar o manifesto mesclado;
não remover componentes por suposição nem adicionar trabalho foreground.
O app continua sem INTERNET.

Fonte: `docs/research/2026-11-16-alertas-locais-monitor.md`.

## 11. Privacidade e atualização

- Nenhuma senha, API bancária, servidor, conta de usuário ou telemetria remota.
- Filtro do pacote confirmado antes de ler o conteúdo; alvo vazio continua seguro.
- Dados privados locais e regras explícitas de exclusão de backup/transferência.
- Sem conteúdo bancário bruto no Logcat.
- Exportação continua explícita, sensível e não anonimizada; não compartilhar sozinha.
- Histórico principal minimiza dados pessoais; detalhes brutos ficam no diagnóstico.
- Atualizar o APK existente sem desinstalar ou apagar os dados capturados.

Não testar apagando a instalação do usuário nem alterar permissões, bateria,
Não Perturbe ou aplicativos bancários automaticamente.

## 12. Evidência de validação necessária

### Testes determinísticos

- Marco inicial/correção, histórico coberto e avisos concorrentes ao formulário.
- BRL e saldo negativo/overflow, sem ponto flutuante.
- Uma observação aplicada uma vez; reprocessamento idempotente.
- Mesma quantia em cobranças distintas, repetição, chave reutilizada e conflito.
- Uso antigo com recebimento novo, resumo, remoção, propaganda e formato financeiro inválido.
- Pendência confirmada/ignorada/absorvida por correção e ação sobre marco antigo.
- Ciclo, ciência, pausa, retomada, crédito insuficiente, recuperação e limite alterado.
- Ciência antiga, permissões indisponíveis e competição entre avaliação imediata e worker.
- Clock controlável para prazos, sem loops de espera nos testes.

### Integração Android e migração

- Migração do esquema do diagnóstico sem perda nem reaplicação financeira.
- Transações, recuperação após interrupção e limpeza do diagnóstico preservando saldo.
- Agendamento único, ação de notificação, cancelamento e reavaliação com TestDriver.
- Temas, retomada do sistema, font scale, TalkBack, navegação e estados de erro.
- Manifesto final sem INTERNET, permissões justificadas e regras de backup preservadas.

TestDriver comprova a lógica no ambiente de teste, não pontualidade no Samsung.

### S23, com consentimento

Conferir atualização sem perda, saldo inicial, crédito/débito natural, repetição,
ciência, recuperação e continuidade. Comparar prazos previstos e observados com
tela bloqueada, notificações negadas/canal silenciado, reinício e force-stop separado.

Não exigir transferências para produzir dados; testes voluntários permanecem
inteiramente sob iniciativa e controle do usuário. Não coletar extratos nem dumps
de notificações para preencher lacunas.

## 13. Critérios de aceite e transição

- Aparência e navegação seguem as telas aprovadas nos dois temas.
- Estimativa e histórico se mantêm após fechar/reabrir; passado não reaplicado.
- Casos duvidosos permanecem visíveis sem impacto automático.
- Alertas representam o ciclo atual, respeitam ciência e recuperação, sem prometer som.
- Diagnóstico/exportação permanecem disponíveis sem dominar o uso diário.
- Testes e limites físicos documentados; nenhuma alegação de saldo oficial.
- Não iniciar a fase remota automaticamente.

Após revisão da especificação e aprovação do usuário sobre este documento,
preparar o plano de implementação. O diretório não é Git; não inicializar nem
criar commits como efeito colateral desta documentação.

A revisão independente aprovou o documento sem bloqueios. As recomendações
de preservar avisos concorrentes ao marco, explicitar retomada manual e manter
exclusão de backup foram incorporadas; o plano deverá torná-las verificáveis.
