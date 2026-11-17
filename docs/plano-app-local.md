# Descoberta da próxima versão: monitor local C6 Tag

## Contexto

O usuário informou que o diagnóstico funcionou e deseja avançar para o aplicativo
de uso diário, incluindo a parte visual. Já existem captura nativa, classificador,
histórico SQLite, tela de diagnóstico, exportação e regras de privacidade.

Reaproveitar a base Kotlin/Compose, sem recomeçar o projeto ou alterar o coletor
instalado antes de aprovar o novo desenho.

## Checklist de desenho

- [x] Explorar a base atual, documentação e evidências existentes.
- [x] Oferecer apoio visual no navegador e obter consentimento.
- [x] Esclarecer requisitos da versão local, uma pergunta por vez.
- [x] Comparar duas ou três abordagens com recomendação e limitações.
- [x] Apresentar desenho funcional e visual para aprovação.
- [x] Escrever a especificação aprovada em docs/superpowers/specs/.
- [x] Revisar a especificação.
- [x] Obter aprovação do usuário sobre o documento.
- [x] Preparar plano de implementação após aprovação.

## Escopo da versão local aprovado pelo usuário

- Saldo inicial informado e saldo estimado atualizado por movimentações.
- Histórico financeiro separado do histórico bruto de observações.
- Limite configurável, aviso sonoro conforme permissões Android, repetição e ciência.
- Tela principal clara e ajustes de saldo/configuração.
- Diagnóstico preservado para investigar falhas, sem dominar a navegação diária.
- Apoio remoto a outros responsáveis em uma etapa própria posterior.

## Decisões que não podem ficar implícitas

- Marco inicial: saldo informado não pode sofrer nova dedução de avisos antigos.
- Identidade de movimentação versus chave da notificação e eventos de atualização.
- Snapshot/reconexão não equivalem automaticamente a novos créditos ou débitos.
- Propagandas e formatos desconhecidos não geram movimentações financeiras.
- Períodos sem captura e reconciliação manual precisam ser visíveis.
- Ciência de alerta não significa crédito recebido ou problema resolvido.
- Som/entrega de alertas dependem de permissões, canais e configurações Android.
- A estimativa nunca será apresentada como saldo oficial consultado no banco.

## Limites da evidência atual

O usuário relatou captura das notificações existentes, propagandas sem valor e
crédito novo reconhecido; informou novamente que funcionou corretamente.
Não presumir que isso valida todos os cenários de repetição, reconexão, reboot
e saldo. Tratar essas condições no desenho e no próximo plano de testes.

O diretório continua sem Git; não inicializar repositório nem criar commits como
efeito colateral desta descoberta.

## Apoio visual

Usuário aceitou mockups no navegador. Sessão local:
`http://localhost:56069`, arquivos em
`.superpowers/brainstorm/90831-1794876467/`.
Primeira comparação: três direções visuais da tela inicial, sem funcionalidades
implementadas e com valores fictícios.

## Direção visual confirmada

- Usuário escolheu a opção A, clara e essencial, pela simplicidade e organização.
- Solicita suporte a temas claro e escuro, com escolha individual conforme conforto.
- Manter a mesma hierarquia e organização nos dois temas, não adotar o layout B.
- Usuário pediu tema escuro em preto/grafite com tons de roxo; tema claro pode
  receber lilás/roxo suave para manter identidade entre os dois.
- Manter a distinção semântica de alertas: âmbar para atenção, não substituir
  todos os estados pela cor da identidade.
- Usuário aprovou a prévia roxa v2: tema claro branco/lilás e tema escuro
  preto/grafite/roxo. Preservar layout A e legibilidade nos dois temas.
- Referência aprovada:
  `.superpowers/brainstorm/109706-1794883145/visao-geral-roxo-v2.html`.
- Paleta candidata: claro canvas #f8f7fc, superfície #ffffff, texto #292232,
  secundário #70677e, destaque #6c42ae; escuro canvas #0d0b10, superfície #17141d,
  texto #f2eef8, secundário #b0a6bd, destaque #c6a6f2. Pares textuais principais
  conferidos com contraste acima de 4,5:1; isso não substitui testes completos
  de acessibilidade, tamanho de fonte e estados no Android.
- Proposta: preferências Claro, Escuro e Acompanhar sistema (padrão sugerido),
  armazenadas localmente em cada aparelho, sem exigir conta.
- Antes de detalhar as telas seguintes, confirmar o escopo da versão local
  versus a futura comunicação com responsáveis de apoio.

## Abordagem e navegação aprovadas

- Evoluir o aplicativo atual, sem criar outro APK independente para uso diário.
- Reaproveitar captura, armazenamento e testes; manter diagnóstico dentro de Ajustes.
- Visão geral: saldo estimado, limite, estado da captura e alerta.
- Histórico: movimentos reconhecidos e correções, sem propagandas.
- Ajustes: saldo inicial/correção, limite, som, intervalos, aparência e diagnóstico.
- Fluxos e regras financeiras continuam em desenho; nenhuma implementação da
  próxima versão foi iniciada.

## Segunda sessão visual

O servidor anterior encerrou por inatividade. Nova sessão:
`http://localhost:58301`, arquivos em
`.superpowers/brainstorm/109706-1794883145/`.
Tela proposta: estrutura A nos temas claro e escuro e quatro estados da visão
geral (normal, saldo baixo, ciência/pausa e saldo a conferir).

## Regra de saldo confirmada

- Na configuração inicial, o usuário informa o saldo atual consultado no C6.
- Esse valor é o ponto de partida; avisos anteriores não são reaplicados.
- Correção manual substitui a estimativa atual, não registra recarga adicional.
- Precisaremos definir tecnicamente o marco e os eventos concorrentes/reconectados
  para cumprir essa regra sem confundir horário de captura com lançamento bancário.

## Alertas: comportamento confirmado e pendências

- Usuário escolheu avisos periódicos enquanto o saldo estiver abaixo do limite.
- Confirmar ciência pausa os avisos temporariamente; padrão de 30 minutos aprovado.
- Se recarga/correção elevar o saldo ao limite ou acima, encerrar o alerta.
- Crédito insuficiente para recuperar o limite não deve resolver o alerta.
- Primeiro aviso deve ser somente no telefone principal.
- Avisos aos aparelhos de apoio devem ocorrer depois, como escalonamento.
  Esse requisito permanece registrado para a etapa remota anteriormente adiada;
  não presumir sua implementação na versão local já delimitada.
- Tempos aprovados: repetição a cada 15 minutos e pausa de 30 minutos após
  ciência, ambos configuráveis. Execução Android pode sofrer atrasos.

## Proteção financeira aprovada

- Uma movimentação deve alterar a estimativa apenas uma vez, mesmo com
  atualizações/reapresentações da notificação.
- Propagandas não são movimentações financeiras.
- Quando a identidade de uma movimentação for ambígua, não aplicá-la
  automaticamente: manter pendência e sinalizar saldo a conferir.
- Diagnóstico conserva as observações técnicas; histórico financeiro é separado.
- Não alegar detecção perfeita de duplicidade sem identificador bancário confiável.

## Telas complementares aprovadas

- Usuário aprovou configuração inicial, histórico financeiro e ajustes.
- Referência:
  `.superpowers/brainstorm/109706-1794883145/fluxos-configuracao-historico-ajustes.html`.
- Pesquisa oficial de agendamento, permissões e som concluída, sem implementação
  no APK ou interação com o celular.

## Restrições Android confirmadas pela pesquisa

- Avaliar e publicar o primeiro aviso depois da atualização persistida, sem
  aguardar a próxima execução periódica.
- WorkManager para reavaliação complementar, com intervalo mínimo de 15 minutos
  e execução inexata. Os tempos da interface serão apresentados como previstos.
- Permissão POST_NOTIFICATIONS e canal de alerta são separados do acesso à
  leitura de notificações. Recusa/silenciamento precisam aparecer na interface.
- Preferências de som controladas pelo canal Android; não contornar DND/volume.
- Ciência persistida e associada à identidade do ciclo de alerta; ações antigas
  não podem pausar alertas futuros por acidente.
- Sem alarmes exatos, servidor ou serviço em primeiro plano permanente.
- Fonte: docs/research/2026-11-16-alertas-locais-monitor.md.
- WorkManager 2.12.0: AAR oficial inspecionado em memória, mínimos compileSdk 35
  e AGP 8.6.0 compatíveis com a base 36/9.0.1; minSdk 24 abaixo do minSdk 26 do app.
- Manifesto da biblioteca inclui WAKE_LOCK, ACCESS_NETWORK_STATE,
  RECEIVE_BOOT_COMPLETED e FOREGROUND_SERVICE, sem INTERNET. Não há proposta
  de serviço permanente; auditar manifesto mesclado e justificar componentes.
- Especificação escrita em
  docs/superpowers/specs/2026-11-17-monitor-local-c6tag-design.md.
- Revisão independente aprovada sem bloqueios. Especificados os avisos
  concorrentes ao formulário como pendências identificáveis, a retomada manual
  como encerramento de pausa e a manutenção da exclusão de backup.
- Usuário aprovou o documento escrito e autorizou preparar o plano.
- Plano escrito em docs/superpowers/plans/2026-11-17-monitor-local-c6tag.md,
  dividido em dez tarefas. Revisão pediu tornar obrigatório o preenchimento de
  identidades/evidências dos avisos v1 antes de limpar diagnóstico, sem efeitos
  financeiros retroativos. Ajuste e teste explícito do primeiro aviso integrados
  ao plano. Nova revisão independente aprovada sem bloqueios ou recomendações.
- Nenhuma alteração de produção iniciada nesta fase. Migração/saldo/alertas são
  verificados antes da atualização física, que exigirá nova autorização.

## Handoff da implementação

Plano concluído e revisado. Usuário escolheu opção 2: execução direta nesta
conversa, com testes e checkpoints, sem agentes de implementação por tarefa.
Tarefa 1 concluída, com 32 JVM/47 instrumentados aprovados, build/lint sem erros
e manifesto auditado. Tarefa 2 concluída com 74 JVM aprovados.
Tarefa 3 concluída; 95 JVM aprovados e build/lint sem erros.
Tarefas 4 e 5 concluídas após revisão e verificações;
109 JVM/81 instrumentados aprovados, lint 0 erros/8 avisos.
Coordinator compartilhado com captura e composição Application já presentes.
Os efeitos Android, antes desabilitados no checkpoint da Tarefa 5, foram
habilitados e validados na Tarefa 6; interface principal integrada na Tarefa 7.
Nenhuma atualização instalada no S23. Não instalar o checkpoint.
Tarefa 6 concluída e aprovada em revisão independente somente leitura,
sem achados Critical/Important/Minor (canal, avisos, ciência e WorkManager reais):
109 JVM/103 instrumentados aprovados, lint 0 erros/8 avisos. Primeiro aviso real
validado somente no AVD, sem executar Worker/TestDriver. AVD próprio encerrado.
Nenhuma atualização no S23.
Tarefa 7 concluída e aprovada em revalidação independente após correções de
entrada negativa e cancelamento expirado: temas, abas, referência, visão geral e
permissão em contexto integrados. 112 JVM/126 instrumentados aprovados,
lint 0 erros/8 avisos. Capturas sintéticas nos dois temas conferidas; AVD encerrado.
Próxima: Tarefa 8, histórico paginado, pendências e ajustes detalhados.

- [x] Tarefa 1: baseline, WorkManager e infraestrutura.
- [x] Tarefa 2: regras financeiras e conferência.
- [x] Tarefa 3: ciclo de alerta puro.
- [x] Tarefa 4: migração e store transacional.
- [x] Tarefa 5: comandos, captura e recuperação.
- [x] Tarefa 6: notificações Android e WorkManager.
- [x] Tarefa 7: temas, navegação, configuração e visão geral.
- [ ] Tarefa 8: histórico, pendências, ajustes e diagnóstico.
- [ ] Tarefa 9: integração, acessibilidade e revisão final.
- [ ] Tarefa 10: atualização e validação consentida no S23.
