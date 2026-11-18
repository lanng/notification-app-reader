# Pesquisa: backend gratuito para alertas remotos de saldo

**Data:** 2026-11-17
**Escopo:** preços, limites e requisitos públicos oficiais de Firebase/Google Cloud, Cloudflare Workers/D1 e Supabase para um monitor remoto pequeno, Android-only. Pesquisa documental; nenhuma conta, serviço, implantação ou dispositivo foi configurado. Nenhum provedor foi escolhido.

**Atualização de contexto (2026-11-18):** o levantamento abaixo registra a
comparação anterior à escolha. Depois, o usuário escolheu Laravel na VPS existente
+ FCM, condicionado à capacidade/compatibilidade, e Google Authentication para o
host. Essa composição dispensa Cloud Functions/Firestore/Blaze. A decisão atual
está em [`../plano-monitor-remoto.md`](../plano-monitor-remoto.md); as alternativas
e recomendações históricas abaixo não a substituem.

## Resumo executivo

- **Mecanismo comum:** o host continua sendo a origem financeira e envia atualizações autenticadas ao backend; o backend mantém estado e vencimentos, executa verificações agendadas e pede ao FCM que envie uma notificação ao Android autorizado. Não há API bancária no fluxo. FCM é gratuito, mas o aceite do envio não garante quando — ou se — o aparelho exibirá a notificação. [FCM: servidor e credenciais](https://firebase.google.com/docs/cloud-messaging/server-environment) · [Prioridade de mensagens Android](https://firebase.google.com/docs/cloud-messaging/android-message-priority)

  ```text
  Host (origem da estimativa) ── HTTPS autenticado ──► Backend + banco
  Backend/agendador ── solicitação de envio ─────────► FCM
  FCM ── push ──────────────────────────────────────► Android autorizado
  ```

- **Custo primeiro:** Cloudflare Workers Free + D1 é a alternativa mais clara para avaliar um início a **US$ 0 sem habilitar plano pago**. A página inicial também anuncia cadastro Free sem cartão; confirmar que nenhum produto pago foi ativado durante o cadastro. Tem limites gratuitos rígidos; a viabilidade de assinar/renovar OAuth do FCM dentro de **10 ms de CPU por invocação** ainda não foi medida. [Cloudflare Plans](https://www.cloudflare.com/plans/) · [Cloudflare Free](https://www.cloudflare.com/) · [Workers limits](https://developers.cloudflare.com/workers/platform/limits/)
- **Menos trabalho de backend:** Firebase oferece autenticação, Firestore e FCM integrados, mas **Cloud Functions agendadas exigem Blaze**: uma conta de faturamento vinculada e método de pagamento. O uso pode caber nas franquias sem custo, mas não é uma configuração estritamente sem faturamento; alertas de orçamento não são limite de gastos. [Planos Firebase](https://firebase.google.com/docs/projects/billing/firebase-pricing-plans) · [FAQ de Cloud Functions](https://firebase.google.com/docs/functions/faq-and-troubleshooting#functions-pricing)
- **Supabase Free** tem recursos suficientes para um piloto e Cron pode chamar Edge Functions, mas projetos Free com baixa atividade podem ser pausados após 7 dias; o plano gratuito não lista SLA de uptime. Pro elimina apenas a pausa por inatividade, não garante uptime. Não há confirmação aqui de que Cron evita a pausa; não se recomenda usá-lo como mecanismo keepalive. [Preços](https://supabase.com/pricing) · [Going into prod](https://supabase.com/docs/guides/platform/going-into-prod) · [Cron](https://supabase.com/docs/guides/cron) · [Agendar Edge Functions](https://supabase.com/docs/guides/functions/schedule-functions)
- Nenhuma das opções gratuitas analisadas estabelece uma garantia de disponibilidade ponta a ponta apropriada para alertas operacionais não supervisionados. Se alertas regulares de caminhão forem críticos, não tratar uma franquia gratuita como garantia de operação.

## Comparação

| Opção | Custo, cartão e estado | Agendamento, limites e comportamento ao exceder |
|---|---|---|
| **Firebase + Google Cloud** | FCM sem custo. Firebase Authentication (maioria dos métodos, sem SMS) não custa; Firestore Spark inclui 1 GiB, 50 mil leituras/dia, 20 mil escritas/dia, 20 mil exclusões/dia e 10 GiB/mês de saída. Spark não exige método de pagamento, mas não oferece Cloud Functions. [Preços Firebase](https://firebase.google.com/pricing/) · [Cotas Firestore](https://firebase.google.com/docs/firestore/quotas#free-quota) | `onSchedule` usa Cloud Scheduler e requer Blaze. Blaze vincula conta Google Cloud Billing, que exige método de pagamento. Cloud Scheduler cobra por job, não execução: 3 jobs por conta de faturamento são sem custo; acima disso, US$ 0,10 por job/31 dias. Cloud Functions tem franquia mensal de 2 milhões de invocações, 400 mil GB-s, 200 mil CPU-s e 5 GB de saída; um job/minuto seria cerca de 43 mil invocações/mês. O Artifact Registry tem 500 MB sem custo, mas armazenamento acima disso pode gerar cobrança. [Funções agendadas](https://firebase.google.com/docs/functions/schedule-functions) · [Preços Scheduler](https://cloud.google.com/scheduler/pricing) · [FAQ Functions](https://firebase.google.com/docs/functions/faq-and-troubleshooting#functions-pricing) |
| **Cloudflare Workers + D1** | Free disponível por padrão; Cloudflare anuncia início sem cartão. D1 Free: até 10 bancos, 500 MB por banco (5 GB total), 5 milhões de linhas lidas/dia e 100 mil escritas/dia. D1 não cobra compute ou saída de rede. Worker/D1 não oferece autenticação de usuário pronta para este backend; a autenticação do host/app precisa ser fornecida e validada. [Cloudflare Plans](https://www.cloudflare.com/plans/) · [D1 preços](https://developers.cloudflare.com/d1/platform/pricing/) · [D1 limites](https://developers.cloudflare.com/d1/platform/limits/) | Workers Free: 100 mil invocações/dia; **10 ms CPU por invocação**, 128 MB, até 50 subrequests por invocação e 5 Cron Triggers por conta. Cron aceita execução a cada minuto; 1/minuto equivale a 1.440 invocações/dia, antes das chamadas HTTPS do host. D1 acima de limites diários retorna erros; ao atingir armazenamento, novas inserções e alterações deixam de funcionar. Atingir o limite diário de Workers retorna erro 1027. Os limites gratuitos interrompem trabalho em vez de tarifarem automaticamente; plano Workers Paid começa em US$ 5/mês por conta. [Workers preços](https://developers.cloudflare.com/workers/platform/pricing/) · [Workers limites](https://developers.cloudflare.com/workers/platform/limits/) · [Cron Triggers](https://developers.cloudflare.com/workers/configuration/cron-triggers/) |
| **Supabase Free** | US$ 0; 2 projetos ativos, 500 MB de banco, 50 mil MAU, 500 mil invocações de Edge Function/mês, 2 milhões de mensagens Realtime/mês e 200 conexões concorrentes. Projetos Free **podem** ser pausados após 7 dias se apresentarem baixa atividade; não é uma pausa incondicional de todo projeto. Pro garante apenas que não haja pausa por inatividade — não um SLA de uptime. Requisito de cartão não foi avaliado nesta comparação. [Preços](https://supabase.com/pricing) · [Going into prod](https://supabase.com/docs/guides/platform/going-into-prod) | Supabase Cron usa `pg_cron`; com `pg_net`, pode chamar Edge Functions em um cron. A documentação mostra execução a cada minuto e recomenda guardar segredo no Vault. Limites Free de Edge Functions: 150 s de tempo de parede, 2 s de CPU por chamada (I/O assíncrono excluído) e 256 MB. **Não assumir** que executar Cron impede pausa por baixa atividade: as páginas citadas não confirmam isso. Não usar Cron como keepalive. [Cron](https://supabase.com/docs/guides/cron) · [Agendar Edge Functions](https://supabase.com/docs/guides/functions/schedule-functions) · [Limites Edge Functions](https://supabase.com/docs/guides/functions/limits) |

## Firebase: gratuito no uso não significa sem cobrança habilitada

- FCM é gratuito nos planos Firebase. Firestore tem franquia Spark, mas Cloud Functions só está disponível no Blaze; por isso, o caminho Firebase gerenciado para um servidor agendado exige a vinculação de Billing e um método de pagamento, mesmo que o uso mensal fique dentro das franquias. [Planos Firebase](https://firebase.google.com/docs/projects/billing/firebase-pricing-plans) · [Preços Firebase](https://firebase.google.com/pricing/)
- Cloud Scheduler dá franquia de três jobs **por conta de faturamento**, não por projeto. O preço documentado é por job; a execução a cada minuto não multiplica esse preço. Uma função agendada pode ser configurada para consultar os vencimentos em um único job. [Preços Scheduler](https://cloud.google.com/scheduler/pricing) · [Funções agendadas](https://firebase.google.com/docs/functions/schedule-functions)
- Firebase recomenda budget alerts, mas eles não bloqueiam uso/cobrança. Há um spend cap separado para Cloud Run functions, mas não é um teto universal para toda a conta de faturamento. [Budget alerts Firebase](https://firebase.google.com/docs/projects/billing/budget-alerts) · [Cloud Billing budgets](https://cloud.google.com/billing/docs/how-to/budgets)

### Onde olhar na tabela Firebase para este caso

O recurso que exige Blaze seria **Cloud Functions**, com uma função agendada
`onSchedule` (nome ilustrativo: `processDueAlerts`), acionada pelo **Cloud Scheduler**.
Ela consulta prazos vencidos, solicita push FCM e termina; não fica rodando por
dez minutos aguardando cada lembrete.

Uma proposta com um verificador global a cada minuto resulta em
`60 × 24 × 30 = 43.200` invocações em um mês de 30 dias, antes de retries e demais
funções de sincronização. O envio de cada destinatário não precisa de um job
Scheduler separado. Isso fica abaixo das 2 milhões de invocações mensais,
mas não estima sozinho CPU, memória, leituras/escritas, egress ou armazenamento
de imagens de implantação. A geração/região/configuração final ainda serão escolhidas.

Franquias exibidas e novamente consultadas na página de preços e no FAQ:
2 milhões de invocações/mês, 400 mil GB-s/mês, 200 mil CPU-s/mês,
5 GB/mês de saída, 120 minutos/dia de Cloud Build e 500 MB de Artifact Registry.
Cloud Scheduler tem franquia separada de 3 jobs por conta de faturamento,
não três execuções; o job pode executar várias vezes dentro do mês.

Fontes: [preços Firebase](https://firebase.google.com/pricing),
[funções agendadas](https://firebase.google.com/docs/functions/schedule-functions),
[FAQ de preços](https://firebase.google.com/docs/functions/faq-and-troubleshooting#functions-pricing),
[preços Cloud Scheduler](https://cloud.google.com/scheduler/pricing).

Nenhuma função foi criada ou implantada. Nomes e contagens acima são uma
proposta de dimensionamento, não uma medição do projeto.

## Cloudflare: caminho cardless, com uma condição técnica importante

- O FCM HTTP v1 requer um access token OAuth 2.0 curto associado a uma service account; o token de registro FCM também deve ser mantido em segredo. Workers oferece Web Crypto (incluindo operações de assinatura), secrets criptografados e `fetch` para chamadas externas. Portanto, FCM HTTP v1 é **tecnicamente implementável** sem Firebase Admin SDK em Worker. [FCM server environment](https://firebase.google.com/docs/cloud-messaging/server-environment) · [Web Crypto Workers](https://developers.cloudflare.com/workers/runtime-apis/web-crypto/) · [Workers secrets](https://developers.cloudflare.com/workers/configuration/secrets/)
- A restrição é 10 ms de CPU por chamada agendada no Free. Espera de rede não consome esse orçamento, mas a documentação não mede a assinatura OAuth, importação de chave ou código real de autorização dentro desse limite. Reutilizar/cachear access tokens reduziria assinaturas; isso exige cuidado com persistência, expiração e concorrência. **Não está comprovado sem medição**, e não se presume que um Worker de produção caiba no limite. [Workers limits](https://developers.cloudflare.com/workers/platform/limits/) · [Web Crypto Workers](https://developers.cloudflare.com/workers/runtime-apis/web-crypto/)
- Alterações de Cron podem levar até 15 minutos para propagar; a documentação diz que invocações podem ser perdidas até a propagação. Isso não especifica perda contínua em operação normal, mas também não é uma garantia de disponibilidade/entrega. D1 e Workers também têm limites gratuitos que falham fechando ou retornando erro quando excedidos. [Cron Triggers](https://developers.cloudflare.com/workers/configuration/cron-triggers/) · [D1 preços/limites](https://developers.cloudflare.com/d1/platform/pricing/)

## Requisitos de produto preservados, não redesenhados aqui

- Atenção: host primeiro, primeiro pedido de aviso aos apoios após 10 minutos e repetição prevista a cada 10 minutos. Ciência pausa somente o host, sem adiar os apoios.
- Crítico abaixo de metade: rompe a pausa do host e o avisa primeiro; o aviso crítico dos apoios fica previsto para 10 minutos depois, sem mover o prazo de atenção já existente. São prazos de escalonamento, não botões de adiamento aprovados.
- O servidor precisa operar sobre o último estado enviado pelo host; sem sincronização nova, não conhece saldo atualizado. A cadência do job não torna FCM pontual nem confirma leitura. FCM pode atrasar mensagens normais em Doze; alta prioridade tenta entrega imediata, sem promessa ponta a ponta. [Prioridade FCM Android](https://firebase.google.com/docs/cloud-messaging/android-message-priority)

## Recomendação prática e incertezas

1. **Se a prioridade imediata é manter custo e cadastro de pagamento em zero:** Cloudflare Workers + D1 é o candidato gratuito mais direto para avaliar. Antes de depender dele, validar posteriormente o OAuth FCM sob 10 ms CPU e observar limites/erros — isto não é aprovação de provedor.
2. **Se a prioridade é reduzir implementação/autenticação:** Firebase é mais integrado, mas requer Blaze e método de pagamento; as franquias podem permitir custo de uso próximo de zero, porém cartão e risco de cobranças extras permanecem. Budget alerts, sozinhos, não limitam gasto.
3. **Para alertas operacionais regulares e não supervisionados:** não eleger um plano Free como confiável. Supabase Free pode ser pausado quando há baixa atividade; Pro elimina apenas a pausa por inatividade e não concede SLA de uptime. Para Cloudflare/Firebase, as páginas gratuitas consultadas tampouco documentam uma garantia de disponibilidade ponta a ponta. Avaliar plano pago/SLA separadamente se esse requisito for real.

**Em aberto:** consumo real do Worker dentro de 10 ms; comportamento do projeto Supabase com Cron durante a janela de inatividade (não inferido); disponibilidade dos provedores e do aparelho; e requisitos finais de retenção/segurança para saldo e tokens. Nenhuma dessas incertezas foi preenchida com uma garantia presumida.
