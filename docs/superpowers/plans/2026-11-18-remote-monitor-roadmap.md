# Monitor remoto — roteiro entre repositórios

**Estado:** planejamento; nenhuma tarefa de implementação executada.
**Spec aprovada:** [`../specs/2026-11-18-remote-monitor-design.md`](../specs/2026-11-18-remote-monitor-design.md).

Este é o roteiro de dependências, não um plano executável do backend nem uma
afirmação de que todos os planos de implementação estão prontos.
Cada etapa tem sua própria revisão e evidência. Identificadores/código em
inglês; interface e comunicação com o usuário em pt-BR.

## Ambiente informado e o que não foi verificado

| Item | Informação | Consequência para o plano |
|---|---|---|
| VPS | Ubuntu, PHP 8.3 | Laravel 13 como alvo, sem atualizar PHP global |
| Banco da VPS | PostgreSQL | Persistência e concorrência precisam de testes nesse motor |
| WSL | MySQL | Não trocar/destroçar esse banco; preparar PostgreSQL isolado só com autorização |
| Espaço | Folga informada pelo usuário | Não equivale a medição de CPU/RAM, disco ou SLA |
| Processos | Cron e Supervisor já utilizados | Novo job/processo isolado, sem editar os de outras aplicações |
| PostgreSQL/PHP local | Versões/extensões ainda não confirmadas | Checar no início do trabalho do backend, não presumir compatibilidade |

Em 2026-11-18, a documentação oficial confirma Laravel 13 com PHP >= 8.3,
correções de segurança até 2028-03-17:
[releases](https://laravel.com/docs/13.x/releases),
[deployment/extensões](https://laravel.com/docs/13.x/deployment).
Além dos requisitos Laravel, o driver PostgreSQL exige `pdo_pgsql`.
Fixar dependências em lockfile no futuro repositório, sem “latest” flutuante.
Não foram acessados VPS, bancos, contas ou dispositivos.

## Etapa A — Android: fundação e viabilidade do alerta

Plano executável separado:
[`2026-11-18-remote-mobile-foundation.md`](2026-11-18-remote-mobile-foundation.md).

**Entrega independente:** modelos e testes de faixa/idade do estado, fixtures
do contrato e um protótipo debug de notificação crítica sintética com parada
imediata. Incluir investigação opcional de full-screen intent, aprovada pelo
usuário, com permissão apenas debug e fallback pela própria notificação.
O monitor financeiro atual continua funcionando sem login/rede.
Não introduzir FCM, Firebase, INTERNET, migrations financeiras ou upload.

**Gate:** provas automatizadas da lógica e da construção/cancelamento da
notificação; comportamento audível depende de ensaio físico consentido.
Tela cheia também depende de validação: com aparelho em uso pode haver apenas
heads-up. Testar estado de permissão e ausência de desvio pelo lançamento direto
de Activity; não tratar concessão manual como aprovação de distribuição na Play.
Se o som não cumprir a meta, apresentar evidência e ajustar essa parte com o
usuário, sem inventar FGS, alarme exato, chamada ou overlay como solução.

## Etapa B — Laravel: repo separado, depois de aviso e pasta do usuário

**Antes de escrever qualquer código do backend:** avisar explicitamente;
aguardar o usuário criar a pasta, informar o caminho e confirmar o início.
Não executar Composer, criar projeto, banco, container ou configuração remota
antes desse gate. O roteiro atual não concede essa autorização.

No diretório confirmado, investigar arquivos preexistentes e instruções locais,
confirmar PHP/Composer/extensões e preparar o plano Laravel detalhado com
caminhos reais, comandos TDD e revisão. Nenhum `.php` é criado neste repo mobile.

### Ordem de entregas para o futuro plano Laravel

1. **Bootstrap isolado:** Laravel 13/PHP 8.3, PostgreSQL de teste dedicado,
   configuração segura, PHPUnit, health check sem informações privadas.
   Não ligar o teste ao banco de outras aplicações ou ao banco de produção.
2. **Contrato v1:** copiar as fixtures acordadas e sua versão do repo mobile;
   testar tipos, centavos, campos permitidos e erros. Mudanças incompatíveis
   voltam ao contrato antes de alterar um dos clientes.
3. **`AccessAndPairing`:** validação real Firebase ID token atrás de Interface
   com fake de teste; owner autorizado, credencial por instalação, um host
   ativo, convites aleatórios/expiráveis/uso único, aprovação, limite de três
   apoios, revogação e troca atômica do host. Token FCM nunca autentica leitura.
4. **`SharedMonitorState`:** visão mínima versionada, heartbeat separado de
   atualização financeira, idempotência/conflito/replay e autorização por papel.
   Não transformar heartbeat/login/troca em prova de continuidade financeira.
5. **`RemoteEscalation`:** transições com relógio controlado, precedência por
   nível, pausa só do host, 0/4/10/14, rebaixamento sem adiar, encerramento
   confiável, alteração de limite com confirmação quando encerra.
6. **Outbox e concorrência PostgreSQL:** intenções únicas, jobs sobrepostos,
   transação de estado+intenção, coalescência após queda; troca do host mantém
   ciclo/prazos dos apoios e invalida o destinatário anterior.
7. **`PushDelivery`:** HTTP v1 FCM server-side, TTL/colapso acordados,
   revalidação antes de enviar, backoff limitado, token inválido e logs seguros.
   Fake HTTP verifica payload e falhas sem enviar notificação a uma pessoa.
8. **Operação e recuperação:** comando agendado a cada minuto, fila persistente
   em PostgreSQL inicialmente, worker limitado supervisionado, retenção,
   backup/restauração e descarte de tarefas/credenciais obsoletas após restore.
   Não exigir Redis/Horizon para este volume sem necessidade demonstrada.

**Gate do banco:** testes rápidos podem usar fakes; testes que afirmam atomicidade,
unicidade e locking precisam de PostgreSQL real isolado. SQLite/MySQL não os
substituem. Escolher instalação local ou container com o usuário, sem instalar
Docker ou PostgreSQL implicitamente.

**Gate de serviços:** conta/projeto Firebase e configuração de owner são
autorizados à parte; por padrão implementar contra fakes. Não habilitar Blaze,
Firestore, Functions ou contas pagas. A implementação do verifier real exige
dependência PHP compatível com Laravel/PHP escolhidos e teste de assinatura,
audience/issuer, expiração e revogação; não escrever verificador JWT improvisado.

**Gate de produção:** confirmar versão PostgreSQL, extensões, CPU/RAM, HTTPS,
isolamento, backups, logs e recursos antes de implantar. Planos cron/Supervisor
serão arquivos de exemplo no repo backend, não alterações aplicadas à VPS.
Fila HTTP aceita/FCM aceita/Android processou são resultados distintos.

## Etapa C — Android: sincronização, identidade e papéis

Preparar plano executável próprio depois de estabilizar contrato e conhecer as
Interfaces do backend; não executar esta lista como substituto desse plano.

1. Login Google apenas host; entrada apoio por convite/aprovação; armazenamento
   de credenciais por instalação e revogação; configuração de ambiente explícita.
2. Migração aditiva SQLite v2 → próxima versão, preservando eventos, referências,
   identidades, pendências, histórico e preferências. Outbox ligada ao commit
   financeiro, não à Activity nem a uma leitura eventual de `StateFlow`.
3. Heartbeat WorkManager >= 15 minutos e envio oportunista; captura e primeiro
   aviso local independentes de rede; sem recuperar continuidade por reconectar.
4. FCM e HTTP reais atrás de Interfaces já testadas com fakes; permissões e
   comportamento offline; nenhum segredo administrativo no APK.
5. Árbitro persistido comum entre aviso local, push e contingência conforme
   spec §5.1, com transição provisória/reconciliação, intervalo de 10 minutos
   por episódio, agravamento prioritário e nenhuma rajada após reconexão.
6. Apoio: apenas visão autorizada, confiança, último contato e alerta; cache
   identificado; sem telas/comandos de saldo, limite, captura ou administração.
7. Host: ciência só dele, convites/revogação, troca de aparelho e primeira
   referência. Primeira visão confiável reconcilia o ciclo preservado.
8. Integrar apresentação validada na etapa A, sem assumir que o protótipo debug
   já prova áudio em background a partir de FCM.

Esta fase pode iniciar partes contra um fake explícito, mas não será anunciada
como compartilhamento operacional antes do backend e do teste ponta a ponta.
Ao introduzir INTERNET/Firebase, atualizar os testes de manifesto e o README
que hoje afirmam ausência de internet, sem enfraquecer exclusões de backup.

## Etapa D — integração controlada e implantação consentida

- Endpoint de homologação e contas/aparelhos sintéticos autorizados.
- Host + dois apoios: captura simulada, atenção 0, crítico 4, avisos 10/14;
  ciência/Parar som distintos; recuperação parcial/completa e pendências.
- Dois emissores concorrentes, FCM atrasado/duplicado, token renovado/revogado,
  modo offline/reencontro e host substituído durante ciclo ativo.
- Medir vencimento, tentativa de envio, aceite FCM, processamento Android e
  publicação; não chamar essas medidas de confirmação de som ou leitura.
- Backup/restauração de homologação com bloqueio de replays.
- Gate físico explícito para atualização do S23/outros aparelhos e quaisquer
  sons, permissões, reboot, force-stop ou revogações de teste.
- Deploy separado, com consentimento e rollback que preserve dados; nenhum
  merge, push, DNS, VPS, conta ou cobrança automaticamente autorizados.

## Cobertura da spec e caminho de evidência

| Alegação | Caminho escolhido | Limitação |
|---|---|---|
| Metade estrita sem overflow | A: JUnit com limites e extremos `Long` | Não prova saldo bancário |
| Falta de contato após 1h | A: relógio explícito, borda 59:59/60:00 | Não prova desconexão física |
| Parar som não pausa ciclo | A: sessão sintética + notificação Android | Som real exige ensaio físico |
| API impõe papéis | B: feature tests HTTP com banco isolado | Fake ID token não prova integração Google |
| Agendas e troca preservam ciclos | B: relógio fake + PostgreSQL concorrente | Não garante pontualidade do FCM |
| Estado local e outbox atômicos | C: fixture SQLite v2 e falhas no commit | Sem inferir captura completa |
| Não duplicar toques local/FCM | C: árbitro persistido + testes concorrentes | Crash entre claim/publicação pode perder aviso |
| Entrega operacional | D: homologação + medições + consentimento físico | Sem SLA ponta a ponta ou confirmação de leitura |

Alternativa mais fraca: só testes de domínio (não prova Android/SQL/transporte).
Alternativa mais forte: ensaios controlados em vários fabricantes e restore real
de homologação. Preferir domínio + integração isolada + prova física consentida,
sem obter dados bancários ou mexer no ambiente produtivo para gerar evidência.

## Acompanhamento

- [x] Registrar ambiente informado e compatibilidade documental PHP/Laravel.
- [x] Separar entregas por repositório e gates de autorização.
- [x] Revisar versão original do plano da etapa A; factory fake no limite da
  Activity incorporada para impedir áudio real em testes UI.
- [x] Revisar ampliação aprovada: investigação full-screen opcional no protótipo,
  sem bloqueios. Validação física permanece futura e consentida separadamente.
- [ ] Escolher execução e isolamento do trabalho Android com o usuário.
- [ ] Executar etapa A e relatar evidência/limites, sem declarar remoto pronto.
- [ ] Avisar e aguardar pasta do backend antes da etapa B.
- [ ] Criar/revisar plano executável Laravel no contexto do novo repo.
- [ ] Criar/revisar plano executável Android integrado.
- [ ] Homologação, consentimentos físicos e implantação separados.
