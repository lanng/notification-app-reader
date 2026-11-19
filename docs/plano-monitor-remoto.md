# Descoberta da próxima etapa — acompanhamento remoto

## Estado

Em 2026-11-18, usuário aprovou a especificação consolidada e a passagem ao
planejamento. Laravel na VPS existente + FCM continua condicionado à checagem
de capacidade/compatibilidade. A próxima etapa é preflight e planos separados
para Android e backend, não acesso ao servidor, implementação,
criação de contas, cobrança, implantação ou alteração de permissões/dispositivos.

## Decisões confirmadas

- Host e apoios devem se comunicar pela internet, em locais diferentes.
- Android apenas nesta etapa; iPhone fora do escopo.
- Volume inicial informado: um host e dois ou três aparelhos de apoio, no máximo.
- Arquitetura-base aprovada: backend Laravel dedicado na VPS existente, com FCM
  para entrega, sujeita à checagem de capacidade e compatibilidade.
- O host continua reconhecendo notificações bancárias e calculando a estimativa.
  O servidor receberá uma réplica autorizada, não credenciais nem acesso ao banco.
- Permissões aprovadas: somente o host configura limite, corrige saldo e gerencia
  os aparelhos vinculados. Apoios consultam estimativa, última atualização e
  estado do alerta e recebem notificações, sem editar valores/configurações.
  A API deve impor essa separação, não apenas esconder botões no Android.
- Pareamento dos apoios aprovado: convite temporário e de uso único, por código
  ou link, seguido de solicitação e aprovação explícita no host. Apoios não
  precisam cadastrar e-mail/senha; o host pode revogar o acesso depois.
- Usuário dispensou conta/login Google ou e-mail/senha para o host: troca pouco
  de aparelho e aceita reconfiguração ao trocar. Isso não dispensa autenticação
  da instalação nem autoriza qualquer APK a assumir um host existente.
  Depois, rejeitou depender de operações manuais na VPS para ativação/substituição
  e aprovou Firebase Authentication/Google somente no host, com identidade validada
  no Laravel e substituição de aparelho pelo app. Apoios continuam por convite.
  Autenticar uma conta Google não autoriza acesso a outro monitor: a API deve
  verificar o proprietário e o aparelho host ativo. Recuperar identidade/administração não restaura
  permissões bancárias/captura nem o histórico financeiro que só existia localmente.
- Primeiro aviso de atenção somente no host. Após 10 minutos, avisar os apoios
  e repetir no host, salvo se os lembretes do host estiverem pausados.
- Nova cadência padrão desejada: 10 minutos, não os 15 atuais. Isso é uma meta
  de agendamento, não garantia de entrega pontual no Android.
- Ciência do host pausa somente seus lembretes; não adia nem interrompe apoios.
- Abaixo de metade do limite, o nível passa a crítico. Exemplo: limite R$ 300,
  atenção em R$ 280, crítico abaixo de R$ 150.
- Entrar no nível crítico rompe uma pausa vigente do host e gera novo aviso
  nele. Os apoios recebem o crítico 10 minutos depois, não simultaneamente.
- Agravamento não adia aviso de atenção já previsto. Exemplo aprovado:
  host atenção no minuto 0, host crítico no 4, apoios atenção no 10 e crítico no 14.
- Usuário deseja apresentação sonora mais longa/perceptível para o crítico.
  Isso não significa uma ligação telefônica real ou permissão para burlar DND.
- Objetivo de áudio aprovado: até 30 segundos por aviso crítico no host/apoios,
  sujeito a viabilidade Android. Ação “Parar som” encerra só o toque atual, não
  o ciclo nem os próximos avisos. Usuário pediu destaque sobre a tela, semelhante
  ao despertador, e acesso imediato à parada sem aguardar o áudio terminar.
  Banner prioritário/ação de parada na própria notificação e tela de alerta
  dentro do app aprovados como abordagem-base, sujeitos às configurações Android.
  Tela cheia fica como exploração opcional dependente de validação e consentimento
  específico, não como garantia nem permissão já concedida.
- Os avisos aos apoios devem continuar até a recuperação por recarga.
  Encerramento confirmado: recarga insuficiente não encerra o ciclo.
  Com limite R$ 300, R$ 140 → R$ 190 sai do crítico mas continua em atenção;
  R$ 140 → R$ 320 encerra o ciclo e os próximos avisos, se a recuperação
  for confiável. Pendências relevantes impedem declarar recuperação automática.
- Falta de comunicação aprovada: mostrar último saldo conhecido, horário da
  sincronização e ausência de contato recente. Alertas já ativos continuam,
  identificados como baseados em dado possivelmente antigo; não inferir recarga.
  Um último saldo normal não deve continuar apresentado como confirmação de que
  está tudo certo. Prazo aprovado: 1 hora sem contato recente, para tolerar
  períodos sem conexão enquanto o host acompanha o usuário. Esse prazo não
  altera os avisos previstos a cada 10 minutos; falta de movimentações
  bancárias não significa desconexão.

## Viabilidade técnica e limites

- O WorkManager periódico atual não aceita 10 minutos; seu mínimo é 15 minutos.
- A proposta é controlar os vencimentos no servidor e solicitar push ao Android,
  mantendo captura/cálculo no host. Transporte push não substitui sincronização
  do estado, autorização dos aparelhos ou um banco de dados.
- Sem atualização do host, o servidor só conhece o último estado sincronizado.
  Não deve apresentá-lo como saldo atual, consultar o banco por conta própria
  nem inferir recuperação.
- Som prolongado/insistente precisa de protótipo e validação de Android/canais/
  permissões. Não há garantia de som, leitura, entrega ou funcionamento durante
  force-stop, falta de rede ou configurações restritivas.
- Usuário apontou risco de prejuízo e caminhões parados; os avisos não podem ser
  apresentados como a única garantia operacional. Estado antigo/offline deve
  ser explícito no desenho.

Pesquisa técnica:
[`research/2026-11-17-remote-alert-timing-and-audio.md`](research/2026-11-17-remote-alert-timing-and-audio.md).

## Custos — levantamento documental concluído

Comparar separadamente:

1. Transporte de notificações push.
2. Hospedagem da lógica, autenticação/pareamento, estado e agendamento.
3. Cotas gratuitas, necessidade de cartão/plano com cobrança e comportamento
   ao exceder cotas.
4. Disponibilidade e manutenção; gratuito não equivale a SLA.

Alternativas inicialmente comparadas: Firebase/Google Cloud, Cloudflare Workers/D1
com push FCM, e Supabase com FCM. Após conhecer a VPS/stack existentes, usuário
escolheu Laravel na VPS + FCM como base, condicionada à capacidade.
As contas e eventuais cobranças devem ficar sob controle do usuário.

FCM é gratuito. A opção Cloudflare Free/D1 é candidata para custo zero, mas
o envio autenticado ao FCM ainda precisa ser medido sob o limite de CPU gratuito.
Firebase é mais integrado, porém funções agendadas exigem Blaze/faturamento.
Supabase Free pode pausar projetos com baixa atividade; não é garantia operacional.
Relatório:
[`research/2026-11-17-free-remote-alert-backends.md`](research/2026-11-17-free-remote-alert-backends.md).

### Infraestrutura existente informada depois da comparação

Usuário informou possuir uma VPS que já hospeda outras aplicações maiores,
domínios/DNS gerenciados na Cloudflare e experiência/projeto Firebase com FCM.
As aplicações da VPS são gerenciadas por instalação direta.
Usuário utiliza majoritariamente PHP/Laravel.
Isso não autoriza acesso, alteração ou reutilização de credenciais/dados.

Abordagem-base aprovada: API, armazenamento e agendador isolados na VPS,
em um subdomínio com HTTPS, enviando push pelo FCM gratuito. Nesse arranjo,
Cloudflare pode continuar responsável por DNS/proxy; Workers e Cloud Functions
agendadas não são necessários para executar a regra.

Não é hospedagem gratuita: a VPS já é paga. Pode não haver novo custo fixo se
capacidade, tráfego e demais condições do plano comportarem o serviço.
Precisamos verificar recursos disponíveis, gerenciamento atual, isolamento,
persistência, backups e disponibilidade; queda/manutenção da VPS interromperia
o processamento remoto, e outras aplicações não devem ser prejudicadas.
Workers/D1 continua sendo alternativa de isolamento da VPS, sujeita aos limites
gratuitos já pesquisados. Nenhuma abordagem foi implantada.

Com a experiência informada, a escolha aprovada é um serviço Laravel
dedicado na VPS, usando FCM para entrega: API autenticada, banco próprio/
isolado, Scheduler para consultar vencimentos e fila persistente para envios/
tentativas. Cloudflare pode continuar somente com DNS/proxy. Não há necessidade
de Cloud Functions, Firestore ou Blaze se essas funções forem atendidas na VPS.
A implementação ainda depende do desenho aprovado e da capacidade/compatibilidade
do ambiente; não atualizar PHP nem alterar as aplicações existentes implicitamente.

### Pergunta sobre simplificar mantendo 15 minutos

Usuário perguntou se manter 15 minutos permitiria usar somente FCM.
Isso é uma hipótese, não confirmação de mudança da cadência desejada de 10.
WorkManager poderia continuar com periodicidade local mínima de 15 minutos,
mas não fornece compartilhamento de estado, autorização ou um emissor FCM
confiável. As credenciais administrativas de envio nunca devem ficar no APK.

Alternativa sem backend próprio: estado compartilhado em Firestore, autenticação/
regras de acesso e consultas periódicas pelos apoios, gerando notificações locais.
Isso já não é "somente FCM" e aceita atrasos maiores em segundo plano/Doze;
não equivale ao escalonamento remoto com servidor e push.
Essa alternativa de polling não foi selecionada nem implementada.

## Questões posteriores à decisão de custo

- Conferir capacidade disponível da VPS para um host e até três apoios;
  o baixo volume de mensagens não elimina o consumo fixo do serviço/banco/fila.
- Detalhar substituição segura do host com Google sem rotina administrativa
  na VPS; detalhes de expiração,
  aprovação/revogação dos convites e privacidade.
- Eventual controle do próprio áudio pelos apoios, sem alterar saldo, encerrar
  o ciclo ou impedir escolhas de notificações oferecidas pelo Android.
- Detalhar rebaixamento do nível crítico, deduplicação das cadências e mudanças
  de limite; regra geral de recarga insuficiente já confirmada.
- Estado desatualizado, ausência de captura/rede, filas, retries e duplicatas.
- Duração/forma do som crítico, cancelamento e teste de viabilidade.
- Novo desenho/UX e especificação para aprovação antes de implementar.

## Consolidação

### Separação dos repositórios e início do backend

O spec descreve o sistema integrado, mas este repositório permanece dedicado
ao app Android. O backend Laravel terá repositório e plano próprios.
Antes de começar qualquer código do backend, avisar o usuário e aguardar que
ele crie a pasta no local correto e informe o caminho. Não criar a pasta,
inicializar o backend ou escolher seu destino implicitamente.

Especificação revisada e aprovada pelo usuário:
[`superpowers/specs/2026-11-18-remote-monitor-design.md`](superpowers/specs/2026-11-18-remote-monitor-design.md).
As propostas técnicas do documento também estão abrangidas pela aprovação
expressa do spec. Nenhuma alteração de execução foi realizada.
Primeira revisão independente apontou coordenação de avisos local/remoto e
continuidade de ciclo na troca de host. As regras propostas foram explicitadas
na especificação. A segunda revisão foi aprovada, sem bloqueios para planejamento;
o usuário aprovou o documento após essa revisão. A revisão destacou que
encerramento por redução do limite exige confirmação explícita e não deve ser
confundido com recuperação automática por uma estimativa confiável.

## Checklist

- [x] Recuperar escopo local e requisitos remotos anteriores.
- [x] Esclarecer conectividade e Android inicial.
- [x] Registrar decisões de cadência, ciência e agravamento.
- [x] Pesquisar limites de Android/push/áudio em fontes oficiais.
- [x] Concluir comparação de hospedagem e gratuidade, com fontes.
- [x] Escolher abordagem com o usuário: Laravel na VPS + FCM, condicionada à capacidade.
- [x] Completar desenho, revisar especificação e obter aprovação.
- [ ] Preparar plano de implementação em branch de trabalho.

### Consolidação e aprovação do desenho

- [x] Explorar contexto do projeto, documentação e commits recentes.
- [x] Avaliar necessidade de apoio visual: esta consolidação é textual,
  sem novas escolhas de layout ou mockups.
- [x] Esclarecer requisitos em decisões sucessivas, incluindo 1 hora sem contato.
- [x] Comparar alternativas de arquitetura e registrar a escolha do usuário.
- [x] Apresentar e confirmar as regras principais; separar propostas adicionais.
- [x] Escrever a especificação como desenho para aprovação.
- [x] Commitar a especificação e as decisões documentadas (`cebdcc3`).
- [x] Concluir revisão independente de consistência da especificação (segunda rodada).
- [x] Obter revisão e aprovação do documento escrito pelo usuário.
- [ ] Após aprovação, preparar plano de implementação com os gates necessários.

### Planejamento — próximo checkpoint

- [ ] Receber resumo sanitizado da VPS: sistema, PHP, banco, CPU/RAM/disco,
  gerenciamento, cron e supervisão de processos, sem credenciais.
- [ ] Definir contrato de integração e planos separados por repositório.
- [ ] Mapear arquivos/tarefas e evidência verificável, incluindo regressão local,
  falhas de rede, autorização, agenda, deduplicação e gate do áudio.
- [ ] Revisar o plano antes da passagem à execução.
- [ ] Antes de qualquer código do backend, avisar e aguardar a pasta do usuário.
