# Monitor remoto — desenho para aprovação

**Data:** 2026-11-18.
**Estado:** revisão independente aprovada na segunda rodada; aprovação escrita
do usuário pendente. Não é autorização de implementação, acesso à VPS ou implantação.

## 1. Objetivo e escolha arquitetural

Evoluir o monitor local para um host e até três apoios Android em locais
diferentes, compartilhando a estimativa e escalonando alertas pela internet.
O host continua sendo a única origem financeira; não existe API bancária,
credencial C6 compartilhada, recarga automática ou acesso bancário dos apoios.

Arquitetura-base aprovada, condicionada à capacidade/compatibilidade da VPS:

```text
Host Android ── HTTPS autenticado ──► Laravel + banco + agendador/fila na VPS
                                                │
                                     envio autenticado ao FCM
                                                │
                                     host e apoios Android
```

Cloudflare permanece para DNS/proxy quando desejado. Firebase fornece Google
Authentication para o host e FCM. Não são necessários Firestore, Cloud Functions,
Blaze, Workers ou um computador pessoal ligado para essa arquitetura.
Google Authentication e FCM atendem este volume dentro das camadas gratuitas;
a VPS já é paga. Não prometer custo incremental zero sem conferir capacidade,
tráfego, armazenamento, backups e condições do plano.

O backend será Laravel dedicado, sem misturar tabelas/credenciais com as outras
aplicações. A versão PHP/Laravel e o banco serão fixados no plano somente após
preflight do ambiente; não atualizar PHP global ou interferir nas aplicações
existentes para acomodar este projeto.

## 2. Escopo e permissões

Um monitor tem um proprietário, um host ativo e até três apoios aprovados.

| Papel | Permitido | Não permitido |
|---|---|---|
| Host | Configurar limite/pausa, corrigir saldo local, revisar pendências, dar ciência, gerar/aprovar/revogar convites | Recalcular silenciosamente o passado ou declarar consulta ao banco |
| Apoio | Consultar estimativa, confiança, contato e alerta; receber avisos; parar seu toque atual | Editar saldo/limite, dar recuperação, pausar host/outros apoios ou administrar aparelhos |

As permissões são impostas na Interface HTTP, não apenas por botões ocultos.
Uma instalação de apoio não pede acesso à captura do C6 ou configuração de
saldo local. O próprio usuário mantém os controles Android sobre notificações.

Não fazem parte desta entrega: iPhone, múltiplos hosts simultâneos, cadastro
público de proprietários, consulta bancária, pagamentos, painel administrativo
web completo, espelhamento de notificações brutas ou histórico financeiro remoto.

## 3. Identidade, convites e troca de host

- O host entra com Google via Firebase Authentication. Laravel verifica token,
  projeto/audience, validade e proprietário autorizado; um e-mail informado pelo
  cliente ou um token FCM não constitui autorização.
- O proprietário inicial será autorizado de forma controlada no provisionamento.
  Não permitir que qualquer conta Google assuma este monitor. Essa configuração
  inicial não significa exigir acesso à VPS em cada troca de aparelho.
- Cada instalação tem credencial própria protegida no aparelho e autorização
  revogável. Apenas a instalação host ativa pode publicar estado financeiro.
- A troca de host exige login recente do proprietário e confirmação explícita
  no app. A troca é atômica: revoga a autoridade do anterior e muda a geração do
  host. Envios e ações antigos são rejeitados, mesmo sendo da mesma conta Google.
- Os apoios e o último estado compartilhado não são apagados automaticamente.
  Recuperar administração não recupera histórico financeiro local ou permissões.
  O novo host deve autorizar captura e conferir uma referência antes de assumir
  que conhece o saldo atual; até lá permanece último estado conhecido/a conferir.
- Apoios usam convite temporário de uso único, código/link, pedido de entrada e
  aprovação explícita no host. Conhecer o convite, sozinho, não concede leitura.
- Revogar apoio impede consultas e futuros envios. Quando o app tomar ciência,
  remove os dados compartilhados do seu cache. Não é possível apagar à distância
  uma captura de tela ou garantir remoção instantânea de cache em aparelho offline.

**Defaults técnicos propostos para aprovação escrita:** convite com pelo menos
128 bits de aleatoriedade, validade de 15 minutos, armazenamento do segredo
somente como hash, limitação de tentativas e aprovação antes de conceder acesso.
Não expor segredo administrativo Firebase no APK, Git, logs ou páginas públicas.

**Continuidade na troca de host — proposta para aprovação:**

- A geração identifica a autoridade de escrita, não um novo ciclo financeiro.
  Preservar ciclo, nível conhecido e prazos dos apoios; troca não equivale a
  recuperação, agravamento ou reinício dos 10 minutos.
- Intenções destinadas ao host anterior são invalidadas. A pausa dele não é
  transferida ao novo aparelho: o novo host passa a receber no próximo vencimento
  vigente, sem aviso sonoro extra só pela troca. Pode então dar sua própria ciência.
- Até conferir a referência, o novo host mostra o último estado como “Saldo a
  conferir”. Login/heartbeat não tornam a estimativa antiga confiável.
- A primeira visão financeira confiável do novo host reconcilia o ciclo
  preservado: mesmo nível mantém os prazos; atenção em lugar de crítico rebaixa;
  crítico em lugar de atenção agrava; estimativa no limite ou acima encerra e
  cancela próximos avisos. Sem ciclo ativo, saldo baixo abre um novo.
  Uma visão ainda duvidosa não encerra nem rebaixa o ciclo preservado.

## 4. Réplica, confiança e falta de contato

O host publica somente uma visão mínima: estimativa em centavos inteiros, limite,
preferências de alerta, confiança/pendências em forma resumida, identificação de
ciclo/nível e versões. Sem texto bruto, propaganda, credencial, extrato, CPF ou
conteúdo de diagnóstico. O backend não reinterpreta observações bancárias.

A publicação sai de uma fila durável ligada ao estado já confirmado localmente.
Falha de rede não bloqueia captura, histórico, correção ou primeiro aviso local.
O backend reconhece a versão aceita; não aceitar regressão ou replay financeiro.

Cada estado pertence a monitor, geração de host e revisão crescente. Repetição
idêntica é idempotente; mesma revisão com conteúdo diferente é conflito.
O backend atribui também uma versão crescente ao estado compartilhado, permitindo
que o Android ignore mensagens antigas após mudanças, recuperação ou revogação.

O host tenta confirmar contato também sem movimentações, com heartbeat periódico
compatível com WorkManager (previsão de 15 minutos, inexata) e sincronização
oportunista em eventos/reabertura. Só contato autenticado do host ativo renova
o horário de contato; um apoio consultar o backend não o renova.

**Prazo aprovado: 1 hora sem contato recente.** A idade do último evento financeiro
e o último contato são campos diferentes. Falta de movimentação não é desconexão.
Após esse prazo:

- Mostrar “Último saldo conhecido”, horário e “Sem contato recente com o host”.
- Alertas já ativos continuam, explicitamente baseados em estado possivelmente
  antigo. Não presumir recarga, recuperação ou novo agravamento pela passagem do tempo.
- Um último saldo normal não mantém confirmação visual de “tudo certo”.
- Se o próprio apoio estiver sem sincronizar, mostrar também essa limitação,
  sem confundir seu cache antigo com um contato recente do host.

Pendências, perda de continuidade ou referência em andamento mantêm “Saldo a
conferir”. Recuperação/rebaixamento não confiáveis não silenciam um alerta
conhecido. Saldo ausente/desconfigurado nunca é convertido em zero.

## 5. Regras de nível, precedência e encerramento

Para limite não negativo `L` e estimativa `B`:

| Condição confiável | Nível |
|---|---|
| `B >= L` | Recuperado, encerra ciclo |
| `B < L` e `2 × B >= L` | Atenção |
| `2 × B < L` | Crítico |

Usar aritmética inteira sem overflow ou arredondamento monetário oculto.
Metade é estrita: com limite R$ 300, R$ 150 ainda é atenção, abaixo é crítico.
Limite zero não implica divisão por zero; saldo negativo entra em crítico.

Decisões aprovadas:

1. Primeiro aviso de um nível no host; apoio recebe esse nível 10 minutos depois,
   no padrão desejado. Os avisos seguintes têm cadência prevista de 10 minutos.
2. Ciência pausa somente host; padrão de pausa permanece 30 minutos e escolhas
   locais existentes serão preservadas. Apoios não são pausados por essa ação.
3. Entrada no crítico rompe pausa existente do host e solicita novo aviso nele.
   O host pode dar uma nova ciência do nível atual; uma ação antiga não o pausa.
4. Agravamento não adia atenção já prevista para apoios.
5. Recarga insuficiente não encerra o ciclo: R$ 140 → R$ 190 sai do crítico,
   mas segue em atenção. R$ 140 → R$ 320 encerra quando não há dúvida relevante.
6. Parar som não é ciência, pausa, recarga ou recuperação.

Linha do tempo aprovada, com intervalo de 10 minutos:

| Momento | Acontecimento |
|---|---|
| 0 | Atenção no host |
| 4 | Agravamento: crítico no host, rompendo sua pausa se existente |
| 10 | Atenção já prevista para os apoios |
| 14 | Crítico para apoios; lembrete crítico do host se ele não estiver pausado |

**Propostas de fechamento do desenho para aprovação escrita:**

- Manter 10 minutos fixos para precedência e repetição remotas nesta etapa.
  Configuração de outras cadências fica fora do escopo inicial; preferências
  locais existentes continuam preservadas para o modo exclusivamente local.
- Ao entrar no crítico, os lembretes de atenção do host são substituídos pelos
  críticos. Apoios mantêm atenção já prevista até começar o crítico; depois,
  um único fluxo crítico substitui as próximas atenções, sem duplicar dois fluxos.
- O aviso de atenção ainda devido usa apresentação de atenção e texto genérico,
  sem mostrar saldo antigo ou afirmar que a situação atual não é crítica.
  Consultar o app sempre mostra o estado mais recente disponível.
- Ao rebaixar de crítico para atenção, interromper toque crítico em andamento
  quando a atualização chegar, atualizar apresentação e manter um único fluxo
  de atenção, preservando o próximo vencimento vigente. Se o primeiro aviso aos
  apoios ainda não venceu, preservar sua data e apresentar atenção, não crítico.
- Alterar o limite reclassifica a estimativa confiável pelas mesmas faixas:
  normal → baixo abre ciclo; atenção → crítico agrava; crítico → atenção rebaixa;
  mesma faixa preserva ciclo/prazos. Se o novo limite permitir encerrar, pedir
  confirmação explícita ao host e registrar “Encerrado por alteração do limite”,
  nunca “Recarga recebida”. Pendências continuam impedindo encerramento automático.
- O relógio remoto é do servidor: o início de nível é registrado quando recebe
  o estado do host, não quando alguém supostamente leu/ouviu a notificação.
  O primeiro aviso local pode ocorrer antes. Sem rede, os apoios só podem ser
  avisados depois que o backend conhecer o evento; não há retroatividade pontual.
- Na primeira ativação do compartilhamento com saldo já baixo, iniciar agenda
  remota a partir do estado aceito, sem emitir uma fila de avisos passados,
  preservando ciência local válida. Reconexão posterior não reinicia o ciclo.

O agendador consulta vencimentos a cada minuto; isso pode acrescentar atraso de
verificação, além do transporte. Após atraso/queda, coalescer vencimentos em
um aviso atual elegível, não enviar rajadas de intervalos perdidos.

### 5.1. Coordenação dos avisos no host — proposta técnica para aprovação

O identificador local atual `EmissionIntent(cycleId, version, requestedAtMs)` não
é, sozinho, uma identidade compartilhada. A evolução deve distinguir revisão
financeira, episódio de nível e ocorrência sonora:

- A entrada/agravamento gera um identificador durável de transição no host, antes
  do aviso local e da fila de sincronização. O servidor reconhece esse mesmo
  identificador; aceitar a transição não pede outro primeiro toque ao host.
- O servidor mantém identidade estável do ciclo e de cada episódio. A resposta
  vincula a transição local a essas identidades e fornece a âncora, a cadência e
  os próximos vencimentos, permitindo numerar os seguintes mesmo sem novo contato.
  Na troca de host, vincula ao ciclo preservado, não cria ciclo pela nova geração.
- Cada lembrete tem chave `(monitor, ciclo, episódio, destinatário, número do
  vencimento)`, atribuída pelo servidor e repetida sem alteração em retries.
  A contingência local usa a chave do último vencimento conhecido e ainda devido;
  se perdeu vários, considera só o mais recente. Apoios não agendam contingência.
- Recebimento FCM, aviso local e contingência passam por um único árbitro durável
  no Android. Ele valida autoridade/versão/ciclo/episódio, pausa e recuperação,
  e reivindica a ocorrência atomicamente antes de solicitar a notificação.
  Ocorrência já reivindicada não toca de novo; atualização visual pode ocorrer.
- Antes da primeira confirmação do servidor, a contingência usa chaves locais
  ligadas à transição. Na reconciliação, transmite também o registro da última
  solicitação local e abandona a agenda provisória, sem reproduzir avisos passados.
- Como relógios e agendas provisórias podem divergir, o árbitro aplica ainda
  intervalo mínimo de 10 minutos entre solicitações sonoras do mesmo episódio
  no host, usando tempo monotônico e registro persistido. Uma solicitação local
  às 15 e push às 20 resultam em um toque, não dois; esse push não é reprogramado
  como toque atrasado. Um novo agravamento legítimo pode furar esse intervalo,
  mas retransmitir a mesma transição não. Sem evidência temporal segura após reboot,
  reconciliar antes de repetir por contingência; não inventar pontualidade.
- Nova visão local ainda não sincronizada prevalece sobre push antigo no host:
  recuperação confiável cancela, ciência suprime apenas host e agravamento abre
  outro episódio. Servidor só aplica essas ações quando as recebe; o app não
  precisa de rede para suprimir localmente.

Isso evita duplicação comum e corridas, não garante som exatamente uma vez:
crash entre registro e chamada Android pode perder uma apresentação. Preferir
não repetir cegamente o som; o próximo vencimento permanece elegível.
Contingência e rede atrasada podem reduzir a cadência efetiva; exibir degradação.

## 6. Android: apresentação e parada do som

Dois canais separados, atenção e crítico, com configurações controladas pelo
usuário. Não alterar silenciosamente um canal já personalizado.

- Meta crítica: até 30 segundos por aviso, com parada antecipada acessível.
- Heads-up quando permitido, ação “Parar som” na própria notificação e destaque
  dentro do app quando aberto. Não bloquear a Interface de usuário durante áudio.
- A parada vale apenas para o aviso atual, inclusive nos apoios; ações antigas
  não podem silenciar um aviso novo. Abrir o app não exige aguardar o som acabar.
- Default proposto: tela bloqueada com conteúdo genérico, sem valor do saldo;
  detalhes dentro do app. Acesso a ações na tela bloqueada depende do Android.
- Notificações bloqueadas/silenciosas aparecem como limitação, sem alegar envio
  ouvido, leitura ou funcionamento garantido.
- Tela cheia é exploração opcional, não parte garantida da primeira entrega:
  requer elegibilidade, consentimento/configuração específicos e validação.
  Não usar overlay, acessibilidade, falsa ligação ou bypass de DND.

**Gate de viabilidade obrigatório antes de prometer áudio pronto:** protótipo
com dados sintéticos para duração, parada imediata, canal/volume/DND, app aberto/
fechado e tela bloqueada. Teste em AVD/fake não prova som no S23. Se não cumprir
os objetivos, apresentar o resultado e rever o desenho com o usuário antes de
dar essa parte como entregue. Nenhum teste sonoro físico é autorizado por esta spec.

FCM é transporte, não prova de entrega. Mensagens carregam identidade de monitor,
ciclo/episódio, destinatário e versão; validar antes de efeitos locais.
Default técnico proposto: TTL de até 5 minutos e colapso de mensagens obsoletas,
revisado no protótipo do transporte. Novos estados/recuperação cancelam intenções
pendentes, mas não se promete apagar instantaneamente mensagens já em trânsito.
Credenciais FCM são endereço de entrega, não autorização de acesso ao saldo.

## 7. Modules e Interfaces

Poucas Interfaces com regras concentradas na Implementation; não espalhar o
controle de ciclos entre controllers, receivers, telas e jobs.

| Module | Interface externa | Implementation escondida |
|---|---|---|
| `AccessAndPairing` | Autorizar host, solicitar/aprovar/revogar apoio, confirmar troca de host | Verificação Google, credenciais por instalação, limites, segredo de convite, geração ativa |
| `SharedMonitorState` | Aceitar visão versionada/contato do host e consultar visão autorizada | Ordenação, idempotência, confiança, cache/idade e persistência |
| `RemoteEscalation` | Aplicar transição/ciência/retomada e avaliar vencimentos com relógio | Precedência, níveis, pausas por destinatário, fila de intenções e cancelamentos |
| `PushDelivery` | Submeter intenção elegível e registrar resultado seguro | OAuth/FCM, expiração, retries limitados, invalidação de token, falhas sem dados privados |
| `AndroidRemoteMonitor` | Enviar estado confirmado e aplicar atualização/aviso autorizado | Outbox, reconciliação de versões, cache por papel, deduplicação e apresentação |

Relógio e transporte têm Seams testáveis: Adapter real e Adapter de teste
controlado. Testes de regras cruzam as mesmas Interfaces que os chamadores.
Persistência/concorrência precisa de provas com o banco real selecionado.

Não efetuar rede segurando transação financeira. Intenções duráveis e identificadas
precedem o envio; workers verificam autorização/versão/estado antes da tentativa.
Usar unicidade de intenção por ciclo/episódio/destinatário/instante planejado.
Retries não são movimentações, não duplicam estado e não prometem áudio exatamente
uma vez perante crash/rede. Diferenciar tentativa, aceite FCM e processamento no app.

## 8. Compatibilidade, dados e operação

- Preservar `br.com.c6tag.diagnostico`, instalação, referências, histórico,
  observações, decisões e configurações existentes; migrações aditivas testadas.
- Login é para compartilhar/administrar, não para continuar usando captura e
  finanças locais. Google/servidor indisponíveis não devem bloquear uso local.
- Apoio novo tem fluxo próprio por convite, sem tela de saldo inicial/captura.
- Cadência remota de 10 minutos não vira WorkManager de 10 minutos. Contingência
  local usa período permitido, ao menos 15 minutos, explicitado na Interface.
  Deduplicar com avisos remotos para não dobrar som; agravamento novo continua prioritário.
- Preferências antigas não são silenciosamente substituídas: ativação remota
  apresenta os novos parâmetros para confirmação, sem redefinir o saldo.
- Compartilhar só a visão mínima autorizada. Não enviar diagnóstico/histórico
  bruto ou fazer upload implícito de exportações. Não há backup financeiro
  completo como consequência de login Google.
- Backend com HTTPS, segredos fora do webroot/Git, banco/usuário isolados,
  permissões mínimas, rate limiting e logs sem tokens/valores/textos bancários.
- Proposta de retenção: visão financeira atual, não ledger remoto; metadados
  operacionais sem valores/tokens por até 30 dias. Restaurar backup deve impedir
  replays de versões/tarefas antigas; isso precisa de ensaio, não suposição.

Preflight antes de código/deploy: PHP, banco, sistema operacional, recursos
disponíveis, isolamento, fila, cron, supervisão, HTTPS, backup/restauração e
monitoramento da VPS. Só informação sanitizada, sem pedir credenciais nesta conversa.
Usar projeto Firebase dedicado preferencialmente; reutilizar um existente exige
autorização explícita e análise de usuários/cotas/credenciais das outras aplicações.

Implantação e alterações de DNS/VPS/Firebase, ativação de cobrança, instalação
física, permissões, reboot ou force-stop são consentimentos separados.
Sem falha silenciosa: se VPS/fila/FCM estiverem indisponíveis, sinalizar degradação.
Não apresentar esta aplicação como única proteção contra perdas/parada de caminhões.

## 9. Evidência exigida antes de conclusão

1. Local: regressões financeiras atuais, migração e outbox pós-commit sem
   retroatividade/duplicatas e sem bloqueio por falha de rede.
2. Identidade: Google inválido/outro projeto/owner diferente, apoio tentando
   escrever, convite expirado/repetido, aprovação/revogação, troca de host e
   rejeição do host antigo; nenhum dado antes de aprovação. Na troca com ciclo
   ativo, preservar prazos dos apoios, não transferir pausa, reconciliar níveis
   e encerrar apenas com nova recuperação confiável.
3. Protocolo: retries, mesma revisão idêntica/conflitante, mensagens fora de
   ordem, token renovado, revogação offline, cache antigo e restauração de backup.
4. Regras: relógio controlado para a linha 0/4/10/14, pausas rompidas só por
   agravamento, ciência/Parar som distintos, recuperação insuficiente, pendências,
   metade exata/centavos ímpares/zero/negativos/overflow e mudanças de limite.
5. Contato: sem movimento com heartbeat válido não fica antigo; aos 60 minutos
   sem contato expõe limitação sem criar recuperação ou novo agravamento.
6. Scheduler/fila: execuções sobrepostas, falha entre persistir/enviar, atraso
   prolongado sem rajada e descarte de intenção obsoleta; banco real selecionado.
   No Android, entrega local/FCM concorrente da mesma ocorrência, chaves
   provisórias reconciliadas, contingência às 15/push às 20, recuperação/ciência
   offline e agravamento legítimo durante intervalo mínimo.
7. Android: host/apoio, temas/fonte/navegação/TalkBack, negação de permissões,
   áudio/parada, reconexão e fallback. Prova física separada e consentida.
8. Infra: medições de consumo e funcionamento do cron/worker, TLS/segredos,
   backup/restauração e observabilidade antes de produção. Nenhum teste contra
   aplicações existentes ou dados reais sem autorização.

## 10. Próxima etapa

Revisar este desenho, incluindo os defaults técnicos propostos, com o usuário.
Só após aprovação escrita coletar os dados de preflight e preparar um plano
de implementação faseado, com gates de áudio, backend, sincronização e validação.
Não há código novo de Android/Laravel, conta ou serviço implantado por este documento.

Referências:
[descoberta](../../plano-monitor-remoto.md),
[vocabulário](../../../CONTEXT.md),
[Android/áudio](../../research/2026-11-17-remote-alert-timing-and-audio.md),
[infraestrutura/custos](../../research/2026-11-17-free-remote-alert-backends.md).
