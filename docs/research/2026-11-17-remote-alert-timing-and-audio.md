# Pesquisa: viabilidade de alertas remotos de saldo e áudio

**Data:** 2026-11-17
**Escopo:** limites atuais de Android, Firebase Cloud Messaging (FCM) e Google Play para alertas Android remotos, incluindo lembretes a cada 10 minutos e áudio repetido. Fontes oficiais consultadas nesta data. Pesquisa documental: nenhum app, backend, conta ou dispositivo foi configurado ou testado.

**Atualização de contexto (2026-11-18):** a arquitetura-base escolhida depois desta
pesquisa é Laravel na VPS existente + FCM, condicionada à capacidade, não Cloud
Functions. O desenho atual registra 1 hora para falta de contato e meta sonora
de até 30 segundos, ainda sujeita a protótipo. Ver
[`../plano-monitor-remoto.md`](../plano-monitor-remoto.md).

## Resumo executivo

- Para reavaliar o último estado sincronizado e solicitar lembretes a cada 10 minutos sem depender do agendador do telefone, recomenda-se agendamento no servidor. O host continua sendo a origem da estimativa: o servidor não consulta o banco nem descobre uma recarga que ainda não foi sincronizada. Uma função agendada do Firebase pode ser invocada pelo Cloud Scheduler nos intervalos definidos; isso não torna a entrega ao telefone pontual. Firebase é uma alternativa pesquisada, não uma escolha de provedor já aprovada. [Cloud Functions: funções agendadas](https://firebase.google.com/docs/functions/schedule-functions)
- No telefone, WorkManager periódico não atende a 10 minutos: seu intervalo mínimo é 15 minutos, e o sistema pode atrasar ou deixar uma execução aguardando constraints. [PeriodicWorkRequest](https://developer.android.com/reference/androidx/work/PeriodicWorkRequest) · [Definir trabalho com WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
- FCM é transporte oportunista, não um relógio nem confirmação de entrega/leitura. Prioridade alta tenta entrega rápida, mas pode ser rebaixada; mensagem aceita pelo FCM ainda pode atrasar, expirar ou não chegar. [Prioridade de mensagens FCM](https://firebase.google.com/docs/cloud-messaging/android-message-priority) · [Tempo de vida de mensagens FCM](https://firebase.google.com/docs/cloud-messaging/customize-messages/setting-message-lifespan)
- Caminho de menor risco: notificação normal com canal e som controlado pelo usuário. Tratar áudio repetido como hipótese a testar, não como requisito garantido. Evitar reprodução de áudio própria em segundo plano, serviço em primeiro plano permanente, alarme exato, bypass de Não Perturbe (DND) e intent de tela cheia para um alerta comum.

## Cadência e entrega remota

- **WorkManager no cliente:** trabalho periódico tem mínimo de 15 minutos e é inexato; Doze, constraints e otimizações do sistema podem postergar a execução. O atraso inicial de um `OneTimeWorkRequest` também é um mínimo, não um horário exato. Portanto, não implementar o ciclo de 10 minutos no Android. [PeriodicWorkRequest](https://developer.android.com/reference/androidx/work/PeriodicWorkRequest) · [Atrasos e trabalho periódico](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
- **Servidor:** manter uma réplica versionada do saldo estimado autorizado pelo host, limiar, pausas por destinatário e próximo vencimento, com data da última atualização; a função agendada consulta o que está devido e solicita o envio pelo FCM. Estado antigo não pode ser apresentado como saldo atual nem confirmar recuperação. A documentação do Firebase confirma que `onSchedule` usa Cloud Scheduler para invocar código nos intervalos definidos. Ela também alerta que uma execução seguinte pode começar enquanto a anterior ainda roda; a regra precisa ser idempotente e deduplicar cada alerta. [Funções agendadas](https://firebase.google.com/docs/functions/schedule-functions)
- **FCM:** prioridade alta tenta entregar imediatamente e pode acordar o aparelho para processamento limitado, mas mensagens de alta prioridade devem produzir notificação visível ao usuário. O FCM pode rebaixá-las se não resultarem em notificações; logo, não é uma garantia de latência. Com `AndroidConfigV2`, a documentação atual recomenda `RemoteNotification` para mensagens visíveis ao usuário, sem “atraso intencional”; isso não elimina os demais limites de entrega. [Prioridade e rebaixamento FCM](https://firebase.google.com/docs/cloud-messaging/android-message-priority) · [Receber mensagens no Android](https://firebase.google.com/docs/cloud-messaging/android/receive)
- Um ID de mensagem retornado pelo FCM significa **aceita para entrega**, não entregue. Se o telefone estiver desconectado, a mensagem pode ficar armazenada. O TTL Android vai de 0 a 28 dias, com padrão de quatro semanas; TTL zero descarta mensagens que não possam chegar imediatamente. Para alertas de saldo que ficam obsoletos, definir TTL curto conforme a regra de negócio e ignorar duplicatas ou eventos antigos no cliente. [Tempo de vida de mensagens FCM](https://firebase.google.com/docs/cloud-messaging/customize-messages/setting-message-lifespan)
- O callback de recebimento tem uma janela curta. Para mostrar um alerta, enviar no payload o conteúdo suficiente e publicá-lo de imediato; não depender de uma consulta de rede adicional antes de exibir. A entrega de FCM também não comprova que a pessoa viu ou reconheceu a notificação. [Receber mensagens no Android](https://firebase.google.com/docs/cloud-messaging/android/receive) · [Prioridade de mensagens FCM](https://firebase.google.com/docs/cloud-messaging/android-message-priority)

## Notificações, som e permissões

- Desde Android 8 (API 26), notificações usam canais. O canal pode definir som, mas o usuário controla importância, som e visibilidade; após criado, o app não pode alterar programaticamente esses comportamentos. [Canais de notificação](https://developer.android.com/develop/ui/views/notifications/channels)
- Desde Android 13 (API 33), `POST_NOTIFICATIONS` é permissão de runtime para notificações comuns. Em instalação nova, notificações começam desativadas; se a pessoa negar, não contar com a exibição. Solicitar em contexto, explicar o benefício e manter o estado do alerta acessível dentro do app. [Permissão de notificações](https://developer.android.com/develop/ui/compose/notifications/notification-permission)
- Som, heads-up e visibilidade dependem da permissão, configurações do canal, volume/modo silencioso, DND e comportamento do dispositivo. `Notification.FLAG_INSISTENT` é documentado como repetição do áudio até a notificação ser cancelada ou a janela de notificações ser aberta. Isso descreve o sinalizador da API; não garante que a notificação chegue, toque em todo aparelho ou supere as escolhas do usuário. [Notification.FLAG_INSISTENT](https://developer.android.com/reference/android/app/Notification#FLAG_INSISTENT) · [Canais de notificação](https://developer.android.com/develop/ui/views/notifications/channels)
- Um canal só pode bypassar DND em condições específicas, incluindo acesso à política de notificações e uma política ativa que permita canais prioritários. Um alerta de saldo comum não justifica pedir esse acesso nem contornar DND. [NotificationChannel](https://developer.android.com/reference/android/app/NotificationChannel#setBypassDnd(boolean)) · [NotificationManager](https://developer.android.com/reference/android/app/NotificationManager#isNotificationPolicyAccessGranted())
- Reproduzir áudio pelo próprio app é um caminho diferente do som da notificação. Para apps que miram Android 15 (API 35)+, pedir foco de áudio exige que o app esteja no topo ou tenha um foreground service. No Android 17 (API 37), interações de áudio em segundo plano — reprodução, foco e APIs de volume — exigem atividade visível ou foreground service não `SHORT_SERVICE`; para apps que miram API 37 há ainda requisito de capacidade *while-in-use*, com exceção estreita para alarme exato usando `USAGE_ALARM`. Sem atender às condições, APIs de áudio podem falhar silenciosamente. Isso trata de áudio próprio do app e não estabelece que sons normais de canais de notificação sejam bloqueados. [Foco de áudio](https://developer.android.com/media/optimize/audio-focus) · [Mudanças de áudio em segundo plano no Android 17](https://developer.android.com/about/versions/17/changes/bg-audio)

## Alarmes, foreground services e Google Play

- Alarmes exatos são para ações que realmente precisam ocorrer em hora precisa. No Android 14+, `SCHEDULE_EXACT_ALARM` não é pré-concedida à maioria das instalações novas; `USE_EXACT_ALARM` é restrita pelo Play a funções centrais de alarme/timer ou calendário que requerem horários precisos. Um lembrete aproximado de saldo a cada 10 minutos não é um bom motivo para pedir essas permissões. [Alarmes exatos no Android 14](https://developer.android.com/about/versions/14/changes/schedule-exact-alarms) · [Política Play para permissões sensíveis](https://support.google.com/googleplay/android-developer/answer/16558241)
- Android 14+ exige tipos e permissões aplicáveis para foreground services. A política Play exige que seu uso seja benéfico e central à função do app, iniciado pelo usuário ou perceptível, interrompível pelo usuário, não adiável sem prejuízo e limitado ao tempo necessário; recomenda WorkManager quando o gerenciamento do sistema não prejudica a experiência. `mediaPlayback` descreve continuar reprodução de áudio/vídeo, não um serviço genérico para tocar alertas repetidamente. [Tipos de foreground service](https://developer.android.com/about/versions/14/changes/fgs-types-required) · [Política Play para foreground services](https://support.google.com/googleplay/android-developer/answer/13315670)
- O Play restringe intents de tela cheia a funções centrais de chamada ou alarme; para apps que miram Android 14+, somente esses usos têm concessão padrão segundo a política atual. Não mascarar um alerta de saldo como chamada real ou despertador para tentar obter privilégios dessas funções. Categorizar uma notificação, por si só, não concede permissão de tela cheia, alarme exato ou bypass de DND. [Requisitos Play para foreground service e full-screen intent](https://support.google.com/googleplay/android-developer/answer/13392821) · [Notification categories](https://developer.android.com/reference/android/app/Notification#CATEGORY_ALARM)

## Caminho seguro de viabilidade

1. Definir a sincronização autorizada do host para o servidor, tratamento de estado desatualizado, vencimentos, encerramento e pausas por destinatário. A ciência do host não deve pausar os apoios, conforme a decisão de produto já registrada.
2. Fazer um protótipo pequeno, sem serviço contínuo: agendamento servidor-side de 10 minutos, FCM para alerta visível e um canal comum com som opcional. Pedir `POST_NOTIFICATIONS` em contexto. Começar com texto genérico na tela bloqueada se o valor do saldo for sensível.
3. Medir separadamente vencimento no servidor, tentativa/aceitação no FCM e recebimento/publicação no Android. Testar duplicatas, mensagens antigas, TTL, app em primeiro plano/segundo plano, aparelho desconectado e reconexão, Doze, permissão negada, canal silenciado, volume e DND. Isso produz evidência do protótipo, não garantia para todos os aparelhos.
4. Só se o produto realmente exigir repetição sonora, avaliar `FLAG_INSISTENT` e cancelamento explícito da notificação no protótipo e em aparelhos/API-alvo representativos. Não avançar para áudio próprio em segundo plano ou FGS sem uma necessidade central e uma análise separada de Android 17 e política Play.

## Limites e questões em aberto

- Não existe proposta de API bancária ou fonte externa de saldo para o backend: ele dependerá da estimativa sincronizada pelo host. Sem atualização do host, só conhece o último estado recebido; disponibilidade, retenção e regras de aviso sobre estado antigo ainda precisam ser desenhadas.
- Não há garantia ponta a ponta de execução exatamente a cada 10 minutos, entrega FCM, som, exibição ou leitura. Perda de rede, estado do aparelho, permissões, configurações do usuário e restrições do fabricante podem alterar o resultado.
- O efeito audível de `FLAG_INSISTENT`, canais e DND deve ser validado por protótipo; a documentação da API não garante comportamento idêntico entre fabricantes.
- Elegibilidade de foreground service/full-screen intent depende da função real do app e da revisão do Play. Esta nota não é aprovação de política nem validação em dispositivo.

## Complemento — destaque visual e parada imediata (2026-11-18)

- O usuário aprovou objetivo de até 30 segundos por aviso, mas quer poder parar
  imediatamente, sem esperar o áudio terminar ou navegar por telas.
  Isso deve ser uma ação local separada de ciência/pausa e de recuperação.
- Um canal de alta importância pode apresentar notificação destacada (heads-up);
  a exibição depende do Android e das escolhas do usuário. A ação de parada
  pode ficar na própria notificação, com fallback no app; não há motivo para
  bloquear a interface durante a reprodução.
- Tela cheia não equivale a heads-up. A documentação Android 14+ restringe a
  concessão padrão de `USE_FULL_SCREEN_INTENT` a funções de chamada/alarme.
  Também documenta `canUseFullScreenIntent` e a tela de configurações para
  concessão manual. Isso não torna qualquer app elegível na Play nem garante
  que sempre abrirá uma tela: durante uso ativo do aparelho, o sistema pode
  apresentar heads-up em vez de tela cheia.
- Não recorrer a overlay, serviço de acessibilidade, falsa chamada ou bypass
  de Não Perturbe para contornar restrições. Qualquer exploração opcional de
  tela cheia exige consentimento/configuração manual, análise de distribuição
  e protótipo com fallback seguro; nenhuma permissão foi solicitada/alterada.

Fontes novamente consultadas:
[notificações urgentes](https://developer.android.com/develop/ui/views/notifications/time-sensitive),
[Android 14 — full-screen intents](https://developer.android.com/about/versions/14/behavior-changes-14#secure-fsi-notifications).

### Encaminhamento após a pesquisa

O usuário aprovou incluir a investigação prática de full-screen intent no
protótipo debug inicial, além da parada pela notificação. O plano contempla
acesso especial opcional, alternativa sem FSI e comparação entre aparelho em
uso e bloqueado. Somente o manifest debug poderá declarar a permissão.
Isso altera o escopo planejado, não o resultado experimental: nenhuma prova de
abertura automática, tela sobre o bloqueio ou duração audível foi obtida.
Concessão de acesso e ensaios físicos continuam exigindo autorização separada.
Plano: [`../superpowers/plans/2026-11-18-remote-mobile-foundation.md`](../superpowers/plans/2026-11-18-remote-mobile-foundation.md).
