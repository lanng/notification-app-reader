# Monitor local — evidências de implementação

## Estado

Execução com subagents autorizada após a T7. Tarefas 1–8 concluídas e revisadas.
Tarefa 9: integração técnica e checagem dirigida de TalkBack concluídas, esta
confirmada por relato do usuário, sem auditoria exaustiva de telas/temas.
Tarefa 10: S23 atualizado para 1.1/código 2 com autorização; sessão assistida
encerrada com evidência parcial e cenários residuais explícitos no fechamento.
Nenhuma permissão física alterada por comando.

**Visual follow-up concluído por implementação delegada e revisões:** após a checagem funcional,
usuário apontou ícones inferiores pequenos e aprovou a especificação escrita de
vetores de 28 dp, preservando nomes/cores/alvos. Causa conferida em `MonitorRoot.kt`:
ícones eram `Text` com caracteres Unicode. Especificação em
`docs/superpowers/specs/2026-11-17-bottom-navigation-icons-design.md`;
documento, plano e implementação aprovados em revisões independentes.
Verificação do controlador: 112 JVM / 146 instrumentados, zero falhas/erros/
ignorados, lint 0 erros / 8 avisos anteriores. APK gerado para a instalação manual
posterior escolhida pelo usuário. Evidências e novo hash em
`docs/testing/bottom-navigation-icons-results.md`. Nenhum acesso físico nem
instalação pelo assistente nesta etapa.

## Baseline — 2026-11-17

Comando:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

Resultado confirmado: exit 0, BUILD SUCCESSFUL in 1s, 84 tarefas acionáveis.
Uma tentativa anterior com `--quiet` terminou por timeout da ferramenta; o log do
daemon registrou término do build, mas isso não foi tratado como comando validado.
A repetição acima, sem `--quiet`, forneceu o resultado completo e terminou.
Não houve alteração de fonte para tratar esse episódio.

Os 47 instrumentados da fase anterior não foram reexecutados por esse comando.

## Tarefa 1 — WorkManager e privacidade

- WorkManager runtime/testing 2.12.0 fixados; grafo transitivo, metadados e
  compilação aprovados sem elevar SDK/AGP/Kotlin/Compose.
- Manifesto mesclado: somente WAKE_LOCK, ACCESS_NETWORK_STATE,
  RECEIVE_BOOT_COMPLETED, FOREGROUND_SERVICE e a permissão interna de assinatura
  AndroidX. Sem INTERNET, POST_NOTIFICATIONS ou armazenamento.
- JobService protegido por BIND_JOB_SERVICE; foreground service da biblioteca
  não exportado. Nenhum job do monitor ou serviço permanente implementado.
- RED instrumentado: o teste antigo falhou em “no Android platform permission
  should be requested”, pois essa restrição da fase diagnóstica mudou.
- GREEN: teste passou a exigir exatamente a lista documentada do WorkManager,
  preservando asserções de binding/backup e proibição de permissões inesperadas.
- Suíte completa reexecutada no AVD read-only próprio: 47 testes, sem falhas,
  erros ou ignorados. AVD encerrado após a rodada, sem acessar aparelho físico.
- JVM: 32 testes sem falhas/erros/ignorados.
- Verificação final de build/APKs/lint: exit 0, BUILD SUCCESSFUL in 6s;
  lint 0 erros, 8 avisos.

Comando instrumentado:

```powershell
powershell.exe -NoProfile -Command '$env:ANDROID_SERIAL="emulator-5572"; & ".\tools\gradle.ps1" ":app:connectedDebugAndroidTest" "--console=plain"'
```

## Tarefa 2 — caminho de evidência (em andamento)

Alegação: regras puras decidem aplicação única, pendência e novo marco sem
reinterpretar o passado, perder avisos concorrentes ou fazer wrap de centavos.
Escolhido: fixtures sanitizadas e testes JVM determinísticos com instantes/IDs
explícitos; RED por asserção antes de cada grupo de regras. Alternativa mais forte:
integração SQLite na Tarefa 4, necessária para comprovar durabilidade/atomicidade.
Esta etapa não comprova captura Android, migração ou entrega de avisos.

- [x] Débito novo: RED → GREEN.
- [x] Identidade, exclusões, conteúdo normalizado e overflow: RED → GREEN.
- [x] Conferência, concorrência, cancelamento/expiração: RED → GREEN.
- [x] Resolução de pendência por marco e sem edição de valor: RED → GREEN.
- [x] Suíte JVM completa e checkpoint.

Resultado: 74 JVM aprovados (32 anteriores + 24 MovementPolicyTest +
18 ReferencePolicyTest), sem falhas/erros/ignorados. Comando completo de JVM
`:app:testDebugUnitTest --console=plain`, exit 0, BUILD SUCCESSFUL in 3s.
Nenhum instrumentado reexecutado nesta tarefa.

REDs observados: débito novo retornava Ignore; 15 regras de movimentação ainda
ausentes; 16 regras de referência rejeitadas; integração da persistência tardia
com motivo genérico em vez de CONCURRENT_REFERENCE; 10 casos de resolução/
validação ainda ausentes. Todos tiveram GREEN posterior. Os testes de resultado
foram ajustados para assertar o tipo antes do cast e o RED foi repetido por
AssertionError, não tratado como erro de compilação.

Contratos preservados: hashes de todas as variantes precisam ser mantidos pelo
store; o marco guarda abertura/fronteira da sessão e inclusões explícitas; cancelamento
retorna candidatos a reavaliar, não os aplica sozinho; expiração retorna candidatos
retidos e exige nova conferência na integração. O ID do marco é a revisão validada
por comandos, para não invalidar a conferência por observações concorrentes.

## Tarefa 3 — ciclo de alerta puro

Relógio em milissegundos fornecido a funções puras; testar transições e intenções
sem chamar NotificationManager/WorkManager. Distinguir solicitação de emissão de
submissão aceita; a última não comprova som ou leitura. A integração Android da
Tarefa 6 será a evidência complementar.

- [x] Abertura abaixo do limite e igualdade: RED → GREEN.
- [x] Repetição, ciência, pausa e retomada: RED → GREEN.
- [x] Recuperação, configurações e ações antigas: RED → GREEN.
- [x] Falhas, submissão/versionamento e recuperação de intenção: RED → GREEN.
- [x] JVM completa e checkpoint.

RED observado: primeiro caso sem ciclo/intenção; após o GREEN inicial,
14 falhas por asserção nas transições ainda ausentes. GREEN final: 21 casos
de AlertPolicyTest aprovados, incluindo intenções persistíveis/versionadas,
retentativa somente por avaliação externa e prazos sem overflow.

Verificação conjunta após Tarefas 2/3: comando completo de JVM/APK/APKtest/lint,
exit 0, BUILD SUCCESSFUL in 10s. XMLs: **95 JVM**, zero falhas/erros/ignorados;
lint zero erros/fatais, 8 avisos. Instrumentados não reexecutados desde a
Tarefa 1 (47 aprovados naquela rodada). Nenhuma chamada Android de notificação,
Work real, alteração do banco existente ou instalação física.

## Tarefa 4 — caminho de evidência (em andamento)

Fixture v1 criada via SQL original em banco sintético isolado. Comparar schema,
observações, variantes de identidade e ausência de dinheiro após upgrade. Testar
rollback real por payload inválido e limpeza dos registros brutos. Somente AVD
read-only próprio; a atualização do banco instalado no S23 não está autorizada.

- [x] Migração aditiva, backfill e rollback: RED → GREEN.
- [x] Migração → limpeza → marco → reapresentação (incluindo variantes).
- [x] Store, projeção/consulta e comandos transacionais.
- [x] Recuperação, limpeza e suíte instrumentada completa.

### Checkpoint parcial da Tarefa 4

- RED de migração: quatro falhas por asserção, pois o helper ainda abria versão 1
  e não executava validação/backfill. GREEN dos quatro após upgrade aditivo.
- Esquema 2 criado sem alterar a tabela `events` nem o codec JSON 1.
  Acrescentada relação `monitor_reference_inclusions` para IDs explícitos,
  sem esconder essas inclusões em um booleano/cursor amplo.
- Backfill por cursor em transação: publicação, valor/tipo inicial, conflito e
  todas as variantes de hash. Não cria saldo, referência, movimentação ou alerta.
- RED adicional: captura nova antes do marco não guardava identidade e falha
  induzida na identidade não afetava a gravação bruta. GREEN após tornar append
  bruto+identidade atômico. Trigger somente na fixture prova rollback de ambos.
- Limpeza do diagnóstico e reabertura preservam fontes/variantes. Erro de payload
  no upgrade aborta tudo, mantém versão 1 e seus registros, sem esquema parcial.
- Teste antigo de versão futura passou a usar versão 3 (2 agora é suportada).
- Instrumentados completos: **53 aprovados** (47 anteriores + 6 de migração),
  sem falhas/erros/ignorados, no AVD próprio read-only, encerrado após a rodada.
- Verificação final JVM/APKs/lint: exit 0, BUILD SUCCESSFUL in 7s; **95 JVM**,
  zero falhas/erros/ignorados; lint zero erros/fatais e os 8 avisos anteriores.
  O aviso UseKtx temporário foi removido usando a extensão transacional disponível,
  seguido de nova execução completa dos instrumentados e build.

**Ainda não comprovado:** sequência migração → limpeza → marco → reapresentação,
pois os comandos/store financeiros não foram implementados. A gravação normal
atualmente só preserva identidade: ainda não aplica dinheiro nem publica revisões
financeiras/alertas. Não instalar este checkpoint no S23.

**Retomada:** continuar na Tarefa 4, passo 3b e store/consultas/comandos. A
reavaliação de fonte provisória após cancelamento precisa distinguir sua própria
identidade ainda não aplicada de uma publicação já tratada; não passar a própria
fonte provisória como “matchingPublication” e ignorá-la acidentalmente. Usar a
evidência mínima persistida, mesmo se o bruto tiver sido limpo, e testar a
aplicação única dessa retomada.

### Segunda rodada da Tarefa 4 — store e transações

Implementados `MonitorStore`, `SqliteMonitorStore`, `MonitorQueries`,
`MonitorReferenceSql` e `MonitorAlertSql`, além da projeção em `MonitorSql`.
O limite entre persistência e apresentação continua síncrono/privado; o
coordinator IO/serial da Tarefa 5 ainda não foi composto.

Evidence path executado:

- Migração → limpeza → primeira referência → reapresentação da publicação antiga,
  chave reutilizada e novas chaves com as variantes original/enriquecida: saldo
  não se altera; as ambiguidades permanecem pendentes.
- Nova fonte altera saldo exatamente uma vez, inclusive após reabertura.
- Concorrentes do formulário não alteram a referência anterior; confirmação usa
  somente IDs explicitamente incluídos, preserva os demais e protege captura com
  persistência tardia. Fontes deixam de ser PROVISIONAL ao concluir a sessão.
- Cancelamento reavalia evidência mínima persistida (sem texto bancário bruto),
  em ordem e sem considerar a própria fonte provisória como repetição já tratada.
  Sem referência/continuidade não aplica automaticamente. Conflitos continuam pendentes.
- Expiração preserva candidatos, rejeita confirmação antiga e não congela a
  avaliação de novas fontes. `refresh` é um comando explícito, não uma consulta.
- Resolução idempotente, rejeição de marco antigo, quantia ilegível sem edição,
  exclusão explícita e absorção por correção sem gerar receita.
- Trigger de fixture em `monitor_entries` prova rollback conjunto de bruto,
  identidade, saldo e revisão publicada; a tentativa seguinte aplica uma vez.
- Histórico/pendências com cursor exclusivo, filtros, limites 1..100 e leituras
  sem incrementar a revisão. Limpeza conserva revisão financeira e marca a
  indisponibilidade da evidência bruta sem impedir decisões.
- Ciclo/intenção/submissão/pausa/configurações sobrevivem ao clear/reopen.
  Negativa repetida de permissão não avança envio nem gera revisões ilimitadas.
  Aparência tem causa própria e não reavalia o alerta.
- Recuperação confiável encerra ciclo; crédito insuficiente respeita pausa.
  Pendência, novo processo e desconexão preservam a necessidade de conferência;
  reconexão não a apaga. Ações/submissões antigas não ressuscitam ciclo fechado.
- Quantias SQLite inteiras, negativo e overflow sem wrap; saldo reconstituível
  pelo marco e movimentos aceitos, sem impacto das pendências.

REDs da rodada: confirmação ainda retornava INVALID_INPUT (2 casos); comandos de
cancelamento/expiração/resolução ausentes (5 casos); persistência de alertas/
settings/continuidade ausente (6 casos); finalização de fontes provisórias ainda
mantinha 2 registros PROVISIONAL (1 caso). Todos com GREEN posterior.
Uma divergência de precedência de rejeição (`INVALID_TIME` antes de
`STALE_REFERENCE`) foi localizada e corrigida na validação do marco.

Verificação antes da revisão independente: **95 JVM + 76 instrumentados**,
zero falhas/erros/ignorados; build/APKs exit 0, BUILD SUCCESSFUL in 8s;
lint zero erros/fatais e 8 avisos. A suíte Android completa terminou em 37s,
somente no AVD read-only próprio. Revisão independente somente leitura em andamento;
o checkpoint não é uma autorização de instalação nem comprovação de emissão Android.

Manifesto mesclado também revalidado nesta rodada: whitelist WorkManager
inalterada, INTERNET e POST_NOTIFICATIONS ausentes, backup desabilitado.
APKs debug e androidTest gerados; nenhum deles instalado em aparelho físico.

Pequeno ajuste da assinatura orientadora: `beginReference` retorna `MonitorResult`
com sessão e snapshot, permitindo rejeitar entrada inválida por código seguro.
Consultas paginadas preservam `require(1..100)` para parâmetros internos, como o
diagnóstico existente. `ResolvePending` recebe instante do comando sem aceitar valor
editado. A revisão persistida identifica a causa e só é publicada após commit.

## Tarefa 5 — caminho de evidência

Enquanto a revisão de Tarefa 4 é somente leitura, iniciar o ponto serial do
recorder (arquivo fora do escopo da revisão). Usar barreiras/latches já existentes,
sem sleeps, para provar a ordem observação → comando → observação, filtro antes
do supplier, resposta segura em falha e finalização de pedidos ao fechar/cancelar.
Depois, fakes do gateway financeiro/efeitos para testar coordinator e integração
com SQLite no AVD; nenhum efeito Android real nesta tarefa.

- [x] Fila compartilhada e encerramento: RED → GREEN.
- [x] Coordinator e recuperação/bootstrap: RED → GREEN.
- [x] Efeitos pós-commit e proteção contra feedback: RED → GREEN.
- [x] Composição Application e regressões completas.

Ponto serial: três testes novos falharam por asserção com execução direta.
Após enfileirar o comando interno, o teste de cancelamento ainda demonstrou que
recebimento imediato de item já bufferizado podia continuar após cancelar o
worker. O loop agora rejeita o item retirado nesse estado e completa também os
itens restantes. O teste antigo de cancelamento de consulta foi fortalecido para
capturar a falha dentro do async (evitando propagação estrutural não tratada) e
exigir rejeição, não somente término.

GREEN intermediário: suíte JVM completa aprovada após esse ajuste (98 casos,
contagem XML confirmada posteriormente); nenhum diagnóstico de outro pacote é
construído e erros do bloco serial são substituídos por mensagem segura.

### Fechamento da revisão de Tarefa 4

Revisão independente encontrou um Important reproduzível: seis adapters ignoravam
rejeição de horário ao expirar a sessão ativa. Os testes observaram cinco comandos
aceitos com hora anterior à abertura e `refresh` ocultando rejeição da regra de
alerta. Também reproduziram motivo REFERENCE_IN_PROGRESS após o formulário terminar.
Três asserções RED; após propagar a rejeição temporal restou o motivo; corrigido,
os 24 testes do store passaram.

Agora há gate compartilhado de expiração nos adapters, rejeição equivalente no
retorno de `evaluateAlert`, propagação de erro de `refresh` e motivo final
CONCURRENT_REFERENCE. A reavaliação materializa apenas os IDs da sessão antes
de alterar o predicado do cursor, sem carregar texto bruto/histórico integral.
Nova revisão somente leitura confirmou resolução dos dois achados e não
encontrou regressão nos ajustes. Tarefa 4 concluída; não é aprovação do app inteiro.

### Integração de Tarefa 5 — checkpoint revisado

- `MonitorCoordinator` usa a mesma fila do recorder para leituras, comandos e
  efeitos. Bootstrap persistido é enfileirado antes do uso, recupera intenção/
  sessão abandonada e não inventa saldo nem continuidade.
- A Application compõe helper/store/coordinator uma vez. Construção no `onCreate`
  não abre o banco na main: o trabalho é enfileirado no worker IO existente.
- `MonitorAlertEffects` é o limite de efeitos. A composição atual é
  `DisabledMonitorAlertEffects`: não publica, não agenda e nunca retorna sucesso
  de submissão fictício. Os efeitos reais pertencem à Tarefa 6.
- O fluxo observado é o de revisões financeiras, com causa. Aparência, resultados
  de submissão e avaliação interna não disparam tentativas; revisões já tratadas
  por comando direto não se transformam em uma segunda tentativa do collector.
- Chave de sincronização ignora saldo/versão de envio: débito no mesmo ciclo não
  reinstala agendamento. Pausa/recuperação/configuração relevante sincronizam o estado.
- Primeiro efeito parte do comando pós-commit, não de Worker. Antes de submeter,
  ciclo/intenção/versão são lidos novamente; mudança durante sincronização invalida
  emissão antiga. Falha de sincronizar não bloqueia a tentativa direta.
- Falhas de submissão viram resultado seguro persistido, preservando intenção;
  nova entrada externa pode tentar novamente. Falhas do bootstrap permitem
  tentativa posterior, sem fechar o recorder compartilhado.
- Testes reais de SQLite + recorder + coordinator comprovaram transação encerrada
  quando o efeito lê o banco, ordem de captura/conferência, confirmação de
  concorrente, cancelamento e resolução idempotente por esse caminho.
- Testes JVM usam clock controlado e StateFlow fake com barreira após o collector,
  sem sleeps. Verificam ausência de feedback, não só contagem imediata de chamadas.

REDs: bootstrap ausente (2), efeitos/collector/recuperação ausentes (5) e composição
Application ausente (1); GREEN posterior. Testes adicionais cobrem revalidação de
estado, fechamento sem tomar posse do recorder e integração de decisões.

Verificação: **109 JVM + 81 instrumentados**, zero falhas/erros/ignorados.
Build/APKs/lint exit 0, BUILD SUCCESSFUL in 8s; lint 0 erros/fatais e 8 avisos.
Instrumentados completos em 34s no AVD próprio, sem aparelho físico.
Revisão independente somente leitura da Tarefa 5 aprovada, sem problemas
concretos no escopo. O revisor não executou testes: os resultados acima foram
obtidos nesta sessão e confirmados pelos XMLs. AVD próprio encerrado após a rodada.

**Retomada ao concluir a Tarefa 5 (histórica):** Tarefa 6, criar canais/notificações, ciência Android e
WorkManager e substituir a composição desabilitada. O teste do primeiro aviso
real continua obrigatório sem Worker/TestDriver. Não instalar no S23 ou alterar
suas permissões sem nova autorização. As notas anteriores de retomada são
históricas, não substituem este checkpoint.

## Tarefa 6 — caminho de evidência (concluída e revisada)

Alegação: após commit, o adapter Android publica o aviso do ciclo atual com
ciência explícita; WorkManager somente reavalia e não é responsável pelo primeiro
aviso. Permissão/canal/falha não podem parecer envio aceito ou saldo recuperado.
Escolhido: objetos Notification/PendingIntent reais, NotificationManager no AVD
próprio e WorkManager TestDriver/WorkerFactory com gateway controlado. Alternativa
mais forte, pontualidade/visibilidade/som no S23, continua dependente de autorização.

- [x] Canal e construção/identidade de notificação: RED → GREEN.
- [x] Capacidade, publicação real e primeiro aviso sem Worker: RED → GREEN.
- [x] Ciência explícita persistida e ação antiga: RED → GREEN.
- [x] Trabalho único, atraso/intervalos e worker via estado persistido: RED → GREEN.
- [x] Composição real, privacidade, regressões e revisão independente aprovadas.

### Implementação e evidência da Tarefa 6

- `MonitorNotifier` cria o canal `monitor_low_balance_v1` com importância HIGH e
  som inicial padrão, sem restaurar escolhas de canal existente ou tentar DND.
  Notificação privada, ID estável 6001, saldo explicitamente estimado e título de
  conferência quando há incerteza. Quantias formatadas por BigDecimal, sem float.
- Abrir o app não marca ciência. A ação “Entendi” tem PendingIntent explícito,
  imutável e ciclo em `data`, não apenas extras. Receiver próprio não exportado
  valida componente/ação/URI/ID e delega via `goAsync`, sempre finalizando.
- A ciência é persistida pelo coordinator antes de o adapter pedir cancelamento.
  Repetição não estende pausa; ação antiga ou identidade inválida não pausa ciclo atual.
- `POST_NOTIFICATIONS` declarada; captura especial permanece independente.
  Capacidade distingue permissão, bloqueio do app e estado do canal.
  Negativa/ausência/bloqueio/falha não viram submissão aceita; canal silencioso
  pode aceitar aviso visual sem o app restaurar som.
- `AndroidMonitorAlertEffects` substitui a composição desabilitada na Application.
  Sincroniza/cancela depois do commit, publica diretamente pelo notifier e
  persiste o resultado. ACCEPTED só significa chamada de plataforma aceita.
- `LowBalanceScheduler`: trabalho periódico único, sem rede, intervalo mínimo
  15 min e atraso inicial igual ao período. Mesmos ciclo/intervalo preservam ID,
  geração e prazo mesmo com débito/pausa; mudança de período usa UPDATE; ciclo novo
  substitui trabalho antigo; recuperação cancela.
- `LowBalanceWorker`: construtor padrão para runtime, WorkerFactory/gateway de
  teste e `coordinator.refresh()` no uso real. Ignora saldo/ciclo obsoleto em
  inputData, pede retry em falha real e não converte cancelamento em sucesso.
- Primeiro aviso **real**: fixture SQLite → coordinator → adapter → NotificationManager.
  A barreira AccessibilityEvent confirmou publicação; nenhum Worker/TestDriver foi
  disparado, contagem de workers zero, e o único trabalho ficou com atraso 15 min.
- TestDriver simulou atraso e período, sem aguardar 15 min. Gateway real do
  coordinator respeitou pausa persistida, retomou na avaliação elegível e perdeu
  corrida para crédito confiável, que encerrou ciclo/cancelou worker suspenso.
- PendingIntent enviado pelo framework terminou o receiver e não reconheceu um
  ciclo antigo. Outro teste observa o adapter solicitando cancelamento somente
  após pausa/recuperação persistidas, fora da transação.

REDs por asserção: canal/construção (2); capacidade/declaração (3); whitelist
antiga proibindo POST_NOTIFICATIONS (1); ciência (2); scheduler/worker (5);
composição do primeiro aviso real (1). GREEN posterior em cada grupo.
Um erro de compilação de teste (`WorkInfo?`) foi corrigido antes de contar o RED;
nomes de parâmetros do WorkerFactory também foram alinhados para evitar avisos.

Diagnósticos de testes, sem mudança financeira:

- NotificationManager.cancel pode retornar antes de a remoção aparecer em
  activeNotifications. A verificação de sumiço instantâneo foi removida; há prova
  separada da chamada de cancelamento e de sua ordem pós-commit. Não usar sleeps.
- Ao substituir uniqueWork, WorkManager pode podar a linha cancelada antiga.
  O teste aceita CANCELLED ou ausência, mantendo exigência de novo ID e só um ativo.
- Um teste legado de DiagnosticViewModel via dois flows aguardava o erro de limpar
  antes do código seguro chegar no collector. Agora aguarda ambos e compara o enum,
  preservando a proibição de texto privado em erros. Passou isolado e na suíte completa.
- O primeiro comando RED desta rodada exibiu as duas falhas esperadas, mas a
  ferramenta sofreu timeout após o Gradle já ter terminado. Não foi contado como
  comando bem-sucedido. As execuções posteriores usaram saída completa e exit 0.

Verificação final antes da revisão: **109 JVM + 103 instrumentados**, zero
falhas/erros/ignorados. Build/APKs/lint exit 0, BUILD SUCCESSFUL in 7s;
lint zero erros/fatais e os 8 avisos anteriores. Instrumentados completos em 38s
somente no AVD read-only próprio.

Manifesto mesclado auditado: permissões Android exatamente WAKE_LOCK,
ACCESS_NETWORK_STATE, RECEIVE_BOOT_COMPLETED, FOREGROUND_SERVICE e
POST_NOTIFICATIONS. Sem INTERNET, alarme exato, DND ou full-screen. Receiver
próprio não exportado; binding privado do listener e BIND_JOB_SERVICE preservados.
`aapt2 dump permissions` no APK debug confirmou a mesma lista e applicationId
`br.com.c6tag.diagnostico`, além da permissão interna AndroidX de assinatura.
Permissão concedida somente ao nosso pacote no AVD para a prova de publicação;
cenários negado/bloqueado/falha usam a fronteira controlada de plataforma. Nenhuma
permissão, instalação ou aviso foi alterado no S23.

### Fechamento da revisão de Tarefa 6

Revisão independente somente leitura aprovada, sem achados Critical, Important
ou Minor no escopo. O revisor examinou notifier, receiver, scheduler, Worker,
adapter, composição Application, manifesto e testes; não executou Gradle/ADB.
Os resultados de 109 JVM/103 instrumentados acima são da execução desta sessão,
confirmados pelos XMLs. Nenhum código de produção foi alterado após essa revisão.

AVD read-only próprio `emulator-5572` encerrado com `emu kill` (resposta OK);
o processo do emulador confirmou o encerramento. S23 permanece sem atualização
ou mudança de permissões.

**Retomada ao concluir a Tarefa 6 (histórica):** Tarefa 7 — temas aprovados, navegação, configuração/correção
de saldo e visão geral. Seguir o checklist do plano com TDD; não instalar no S23
nem alterar suas permissões sem nova autorização.

**Ainda pendente:** UI/pedido de permissão em
contexto (Tarefas 7/8), acessibilidade final e validação consentida no Samsung.
Nenhum teste comprova som, ciência humana ou pontualidade real.

## Tarefa 7 — caminho de evidência (concluída e revisada)

Alegação: a interface apresenta estimativa e estados reais separados, usa os
temas aprovados e cria/cancela referências somente pelo coordinator, sem valores
arredondados, reaplicação do passado ou concessão automática de permissões.

Escolha: Compose com estado sintético controlado para textos/ações/navegação;
ViewModel com SQLite/recorder/coordinator reais e relógio controlável no AVD
próprio para conferência, concorrência, cancelamento e expiração; testes JVM
para BRL e overflow. Capturas sintéticas claras/escuras complementam a semântica.
Só screenshots seriam evidência fraca para transações; validação física/TalkBack
completa será posterior e exige consentimento no S23.

- [x] RED → GREEN: estados Compose e tokens claro/escuro/sistema.
- [x] RED → GREEN: entrada BRL inteira, negativa, inválida e overflow.
- [x] RED → GREEN: ViewModel, referências, concorrência/cancelamento/expiração.
- [x] Abas, diagnóstico preservado e pedido de avisos em contexto.
- [x] Nome/versão, regressões, capturas sintéticas e revisão do checkpoint.

### Implementação e evidência da Tarefa 7

- `MonitorTheme` usa a paleta aprovada sem cores dinâmicas, fonte remota ou nova
  dependência. Preferência Sistema/Claro/Escuro persistida pelo coordinator,
  inclusive antes de configurar referência, sem alterar limite ou saldo.
- `MonitorRoot` apresenta as três abas; diagnóstico é um destino em Ajustes com
  retorno explícito/back. A Activity mantém SAF, mensagens de falha, acesso
  especial e ViewModel do diagnóstico existentes, sob a nova raiz.
- Overview diferencia não configurado (sem zero fictício), normal, baixo,
  pausado e a conferir. Exibe estimativa, limite, última observação, pendências
  sem impacto automático, autorização/conexão e capacidade de avisos separadas.
  Ciência/retomada carregam o ID do ciclo mostrado; datas são previstas, não promessas.
- Formulário abre sessão real, aceita saldo negativo e limite não negativo,
  valida BRL estrito em centavos e exige revisão/diálogo antes do comando.
  Correção substitui a estimativa e aparece como referência, não crédito.
- Política desta UI para avisos concorrentes: **nenhum é incluído implicitamente
  no saldo** (`explicitlyIncludedObservationIds` vazio). O formulário e o diálogo
  avisam que continuarão individualmente pendentes; o store conserva identidades.
  Uma inclusão individual explícita pode ser feita pela resolução da Tarefa 8.
- Cancelar usa o coordinator (não apenas fechar tela) e reavalia provisórias
  conforme a continuidade. Expiração/marco externo mantém o texto digitado mas
  invalida o envio e orienta cancelar/reabrir. Toque duplo não cria dois marcos.
- ViewModel serializa carregamentos/comandos, observa revisões somente com
  leituras e conserva valores em falha, sem erros privados ou refresh infinito.
  Activity coleta por lifecycle; draft e destino diagnóstico sobreviveram à
  recriação da Activity no AVD.
- Pedido de `POST_NOTIFICATIONS` ligado a explicação + toque explícito.
  Dispensar explicação/recusar avisos não impede saldo/histórico. Configurações
  de canal são nativas; nenhum shell concede permissão no S23.
- Nome visível `Monitor de tags`, versionCode 2/versionName 1.1, applicationId
  original preservado. Isso foi compilado e exercitado somente no AVD.
- Histórico nesta tarefa é **prévia declarada de três registros**, não o
  histórico completo. Paginação, filtros, decisões de pendências e ajustes
  detalhados de limite/intervalos pertencem à Tarefa 8, ainda não implementada.

REDs observados: BRL (2 asserções), estados/tema Compose (4), ViewModel vazio
(6 falhas por timeout de estado inicial), raiz/formulário/tema persistido (4),
Activity/nome e ação de acesso no formulário (3). Um erro de compilação de
assinatura de teste foi corrigido antes de contar RED. Um seletor de texto
encontrou o aviso tanto no formulário quanto no diálogo; restringido ao diálogo,
sem remover a explicação repetida intencionalmente.

Outros testes de regressão/limites passaram sem exigir mudanças: falha de leitura
termina loading e retry recupera; saldo anterior permanece em falha; marco externo
invalida formulário; draft sobrevive à recriação; fonte 2× mantém ações acessíveis
por rolagem. Testes não equivalem a validação completa de TalkBack no Samsung.

Capturas **somente sintéticas** inspecionadas:
`screenshots/synthetic-monitor-light.png` e
`screenshots/synthetic-monitor-dark.png`. O teste renderiza a mesma fixture nos
dois temas; não lê banco real nem abre a cortina de notificações. A extração inicial
após connectedAndroidTest falhou porque o runner removeu o APK de teste/alvo.
Reinstalação e execução instrumentada isolada do teste visual no AVD produziram
as imagens, lidas via run-as. Nenhuma operação foi feita no aparelho físico.

Verificação completa antes da revisão: **112 JVM + 124 instrumentados**, zero
falhas/erros/ignorados. Build/APKs/lint exit 0; lint sem erros/fatais, 11 avisos
(8 anteriores e 3 PluralsCandidate novos ainda a ajustar). Revisão independente
somente leitura em andamento; não é fechamento do checkpoint.

`aapt2 dump badging` confirmou no APK nome `Monitor de tags`, versionCode 2,
versionName 1.1, mesmo pacote e mesmas permissões Android documentadas na Tarefa 6.

### Fechamento da revisão de Tarefa 7

A primeira revisão encontrou um Important (teclado decimal não expunha sinal
negativo) e um Minor (cancelar sessão cuja expiração é descoberta no comando
podia exigir segundo toque). Ambos foram verificados e corrigidos por RED → GREEN:

- O saldo usa teclado textual sem autocorreção; o limite mantém teclado decimal.
  O teste foca o campo, consulta o **EditorInfo real** pela InputConnection e
  rejeita classe numérica sem flag signed. Falhou com o tipo antigo; passou com
  o tipo textual e confirmou que `-20,25` produz `-2025` centavos sem arredondar.
- Cancelamento aceita como resultado bem-sucedido somente `SESSION_EXPIRED`
  com snapshot retornado comprovando que aquela sessão já não está ativa.
  Nenhuma falha genérica é suprimida. Teste real SQLite/coordinator preserva
  saldo/pendência e exige fechamento sem erro após um único toque.
  A primeira tentativa de reprodução foi mascarada por uma avaliação de
  observação ainda enfileirada; concluí-la antes de avançar o relógio tornou
  o RED determinístico. Não houve refresh depois da expiração antes do cancelamento.
- Os três `PluralsCandidate` foram resolvidos com recursos `plurals` e
  `pluralStringResource`; o teste falhou para “1 pendência” antes da correção e
  passou verificando singular e plural.

Revalidação independente somente leitura **aprovada sem achados
Critical/Important/Minor**. O revisor não executou Gradle/ADB. Verificação completa
desta sessão após as correções: **112 JVM + 126 instrumentados**, zero
falhas/erros/ignorados; build/APKs/lint exit 0. Lint **0 erros/fatais e 8 avisos
anteriores**, sem os novos avisos de pluralização. A suíte de interface isolada
teve 23 testes aprovados. Nenhum código foi alterado após a revalidação.

AVD próprio `emulator-5572` encerrado com `emu kill`, resposta OK. Nenhuma
instalação, permissão ou aviso físico foi alterado no S23.

**Retomada ao concluir a Tarefa 7 (histórica):** Tarefa 8 — histórico paginado, filtros, detalhe e resolução de
pendências, limite/intervalos e ajustes de diagnóstico. Reaproveitar tema e link
nativo já integrados, sem tratar a prévia de três registros como histórico completo.
Tarefas 9/10 e validação física continuam pendentes; atualizar o S23 exige
nova autorização explícita.

## Tarefa 8 — caminho de evidência (concluída e revisada)

Usuário autorizou subagents de implementação com revisão, Git local e commits
por etapa. O checkpoint T7 foi registrado em quatro commits (build/higiene,
implementação, testes e documentação), sem reconstruir versões históricas
artificiais, sem remoto/push e sem incluir caches, local.properties ou exports.

Alegação: histórico financeiro paginado e filtrado, decisões explícitas de
pendências e ajustes suportados usam somente o coordinator, preservam identidade,
marco e saldo, e não transformam diagnóstico/propaganda em dinheiro.

Escolha: testes Android com SQLite/coordinator/clock reais para paginação,
concorrência, pendências, limpeza e configurações; Compose com fixtures para
texto, seleção, confirmações e navegação. Capturas sintéticas complementam os
testes. Fakes isolados podem controlar falha/barreira sem substituir as provas
financeiras reais. Validação física continua separada e não autorizada.

- [x] Histórico paginado, filtros e detalhe: RED → GREEN.
- [x] Pendências, confirmação idempotente e marco antigo: RED → GREEN.
- [x] Ajustes de limite, repetição/pausa e estados de falha: RED → GREEN.
- [x] Integração, diagnóstico/limpeza/exportação e navegação.
- [x] Verificação completa, capturas sintéticas e revisões spec/qualidade.

### Entrega e revisão de especificação da Tarefa 8

O implementador entregou histórico paginado por ID local exclusivo (20 registros
com sobreconsulta de um para detectar próxima página), filtros e detalhe
financeiro sem texto bancário bruto. Pendências têm paginação própria, motivo,
valor legível quando disponível e decisão com confirmação explícita.
O marco esperado é capturado ao abrir a seleção; correção externa invalida a
decisão antiga, sem rebinding silencioso ao novo saldo.

Ajustes permitem limite não negativo validado em centavos, repetição 15/30/60 min
e pausa 15/30/60/120 min. Tema persistido da T7 foi reaproveitado. Falha ao abrir
preferências nativas é separada de capacidade de emitir e autorização de captura.
O launcher SAF foi mantido na composição estável da Activity, fora do destino
condicional do diagnóstico; limpeza explica que não apaga o acompanhamento.

Implementador relatou RED dos testes de filtro/linha clicável contra a prévia
anterior e GREEN dos testes focados. Um teste de paginação de pendências expôs
corrida da fixture com reload de revisão; recriar o ViewModel ao redor da carga
sintética estabilizou o cenário. O agente principal conferiu o diff e executou
as suítes completas, em vez de tratar o relato como verificação independente.

A revisão spec encontrou um Minor: faltava texto explícito quando a observação
bruta estava disponível. Corrigido por RED → GREEN relatado no teste focado;
o código agora exibe disponibilidade ou ausência e declara que mostra apenas
metadados mínimos, não conteúdo bruto. Teste alterna indisponível → disponível →
indisponível. Revalidação spec somente leitura **aprovada**, sem achado remanescente.
A primeira tentativa de revalidação foi interrompida por limite de uso; a
aprovação só foi recebida após a retomada, não inferida da tentativa interrompida.

Verificação independente após essa correção: **112 JVM + 144 instrumentados**,
zero falhas/erros/ignorados; build/APKs/lint/connected exit 0. Lint 0 erros/fatais
e 8 avisos anteriores. Contagem 144 vem dos XMLs, não de somar estimativas das
classes (o teste de disponibilidade ampliou um caso já existente).
Revisão de qualidade/final ainda em andamento.

## Tarefa 9 — preparação de integração final (parcial)

Executada em paralelo à Tarefa 8 apenas no escopo documental/estável.
Não é aprovação da integração final.

- README atualizado para o monitor local, distinguindo estimativa, referência,
  correção, pendência, ciência, pausa e recuperação. O estado ainda informa T8 em
  implementação, sem declarar instalação financeira no S23.
- Checklist `monitor-local-s23-checklist.md` preparado, com todas as observações
  físicas pendentes e portão explícito de autorização/artefato/preservação de dados.
  Links locais do README verificados por script; nenhum teste físico alegado.
- Git inicial contém somente fontes, testes, documentação, wrapper e ferramentas.
  112 caminhos revisados antes dos commits, sem padrões detectados de chaves
  privadas/tokens/senhas; caches/configuração local/exports/bancos/APKs ignorados.
  A checagem por padrões não substitui inspeção humana antes de publicar o remoto.

Contraste calculado por luminância relativa WCAG para os pares de tokens aprovados:

| Par | Claro | Escuro |
|---|---:|---:|
| Texto / fundo | 14,39:1 | 17,12:1 |
| Texto secundário / fundo | 5,01:1 | 8,42:1 |
| Destaque / superfície | 6,94:1 | 8,78:1 |
| Atenção / fundo âmbar | 6,21:1 | 9,87:1 |

Todos esses pares superam 4,5:1 para texto normal. A conta não valida componentes
renderizados, estados desabilitados, foco, corte, landscape ou TalkBack.
Essas verificações e a suíte final após T8 continuam pendentes.

Um subagent somente leitura mapeou a cobertura estável de migração/store/alertas.
O agente principal conferiu os testes referidos: migração sem dinheiro,
identidades após limpeza, resolução idempotente/marco antigo, reconstrução do
saldo e pausa/recuperação já têm verificações separadas.

Lacuna identificada: migração de banco preenchido seguida de bootstrap pelo
coordinator real, sem submissão retroativa, e recriação desse coordinator sobre
o mesmo SQLite. Preparado
`ANDROID/monitor/MonitorLifecycleIntegrationTest.kt`, fora do escopo de arquivos
da T8. Ele junta migração/bootstrap e um fluxo de referência, dois débitos,
duplicata, ciência/pausa, pendência, crédito, correção, limpeza e reabertura.
Ainda **não executado**: a linha de Gradle/AVD está reservada ao implementador T8.
Não tratar o arquivo ou a leitura do subagent como teste aprovado.

### Execução independente após entrega da T8

O agente principal executou build/JVM/APKtest/lint e a suíte Android completa:
**112 JVM + 142 instrumentados**, zero falhas/erros/ignorados; lint 0 erros/fatais
e 8 avisos anteriores. Os dois testes de `MonitorLifecycleIntegrationTest`
passaram nesta rodada, fechando a lacuna de migração → bootstrap real sem
submissão retroativa e recriação do coordinator com saldo/histórico/identidade
preservados. A revisão spec da T8 encontrou um Minor sobre exibir explicitamente
a disponibilidade da observação bruta; correção delegada antes da revisão de qualidade.

Adicionado `MonitorFinalLayoutTest` com dados apenas sintéticos. Captura dos três
novos destinos em claro/escuro passou isoladamente; o ensaio de configurações a
200% em restrições retrato 390×844 dp e paisagem 740×360 dp também passou.
Isso simula dimensões/configuração local do Compose, não uma rotação física do S23.

Diagnóstico do teste de alvos de toque: a primeira asserção mediu altura visual
usando densidade da raiz. `ForcedSize` pode mudar a densidade local e um chip
Material pode expandir o hit target além do retângulo visual. Asserções com
`boundsInRoot`/`size` não estabeleciam o tamanho do alvo. A checagem final usa
`SemanticsNode.touchBoundsInRoot` e densidade do conteúdo, exigindo os pixels
correspondentes a 48 dp nominais, com tolerância explícita de apenas 0,001 pixel
para precisão numérica dos limites fracionários (não 0,5 dp);
ações de repetição/pausa/salvar foram acionadas nos dois tamanhos sem alterar
produção. Não reduzir o requisito para “fazer passar”.

Fontes primárias consultadas para esta verificação:
[DeviceConfigurationOverride](https://developer.android.com/reference/kotlin/androidx/compose/ui/test/DeviceConfigurationOverride)
e [SemanticsNode](https://developer.android.com/reference/kotlin/androidx/compose/ui/semantics/SemanticsNode).
APIs públicas, sem dependências novas. Teste semântico não comprova a experiência
real de leitura/foco do TalkBack; essa parte continua explicitamente pendente.

### Artefato e capturas após a retomada

Build completo e suíte Android repetidos após a correção de disponibilidade:
112 JVM/144 instrumentados aprovados e lint 0 erros/8 avisos. Execução direta
adicional de `MonitorFinalLayoutTest` e `MonitorVisualTest`: `OK (3 tests)`,
somente para produzir as imagens antes de o runner remover os APKs.

O agente principal extraiu e inspecionou as oito imagens exclusivamente sintéticas
em `screenshots/synthetic-{monitor,history,pending,settings}-{light,dark}.png`.
As datas de 1969 no histórico de teste vêm dos instantes pequenos das fixtures,
não de dados do usuário nem de uma alegação sobre o relógio de produção.
Campos/ações fora do primeiro viewport dos Ajustes permanecem acessíveis por
rolagem; o teste ampliado aciona repetição, pausa e salvar a 200% nos dois tamanhos.

Manifesto mesclado e APK conferidos: pacote `br.com.c6tag.diagnostico`,
versionCode 2/versionName 1.1, nome `Monitor de tags`; permissões Android
exatamente POST_NOTIFICATIONS, WAKE_LOCK, ACCESS_NETWORK_STATE,
RECEIVE_BOOT_COMPLETED e FOREGROUND_SERVICE, além da permissão interna AndroidX.
Sem INTERNET, alarme exato, DND ou full-screen. `aapt2 xmltree` comprovou
allowBackup=false e recursos empacotados com exclusões para backup e transferência.
BuildConfig gerado confirmou `C6_PACKAGE_NAME=com.c6bank.app`, sem ler ou imprimir
outras propriedades locais.

APK desta rodada: 12.973.580 bytes;
SHA-256 `1eaacb831de78a2fb60d628e5bcffeb0dd72fd808a8aedad1bd3f6fe5ccad1bf`.
Esse hash deve ser recalculado se qualquer código/recurso/build mudar antes de
uma atualização física. Nenhuma instalação ou permissão foi alterada no S23.

### Fechamento técnico de T8/T9

Revisão de qualidade/final não encontrou Critical/Important. O único Minor foi
a inconsistência entre afirmar 48 dp e aceitar 47,5 dp no teste. A asserção agora
compara `touchBoundsInRoot.height` com `48f * densidadeLocal`, admitindo somente
0,001 pixel de ruído numérico, explicitamente documentado. Uma tentativa de
comparar com pixel inteiro arredondado rejeitou 69,90001 px, que já eram 48 dp
na densidade 1,45625; a comparação fracionária evita essa falsa falha sem aceitar
alvo genuinamente menor em unidades dp.

Teste focado e suíte completa repetidos após o ajuste: **112 JVM + 144
instrumentados**, zero falhas/erros/ignorados; build/APKs/lint/connected exit 0,
lint 0 erros/fatais e 8 avisos anteriores. XMLs conferidos pelo agente principal.
SHA-256 do APK permaneceu igual (o último ajuste foi apenas de teste/documentação).
Revalidação de qualidade somente leitura **aprovada**, sem achados remanescentes.
Os revisores não executaram Gradle/ADB; as execuções acima são do agente principal.

**Tarefa 8 concluída. Tarefa 9 parcialmente concluída:** integração técnica,
invariantes, testes de layout/configuração, auditoria do APK e revisão final de
código aprovados. Leitura/foco reais do TalkBack continuam não observados.
Não converter a prova semântica em promessa de acessibilidade completa.

**Retomada atual:** concluir a checagem manual de TalkBack da Tarefa 9 e obter
nova autorização antes de qualquer consulta/atualização física da Tarefa 10.
Manter commits na branch de trabalho; sem merge ou push automático.

Commits locais desta entrega na `feat/monitor-history-and-settings`:

- `e2cb3c8` — implementação da T8.
- `9e04d9b` — testes de paginação, decisões e ajustes.
- `9a1fc95` — invariantes de ciclo de vida e evidência sintética de layout.

Emulador read-only próprio `emulator-5572` encerrado com `emu kill`, resposta OK.
S23 permanece sem atualização, novas consultas ou alteração de permissões.

## Tarefa 10 — sessão assistida autorizada

Após solicitação explícita de conexão USB e autorização de atualização
preservando dados, o usuário confirmou: “Conectado, sim autorizo”.
Reconferido exatamente um alvo físico autorizado: SM-S911B, Android 17/API 37.
Origem C6 confirmada anteriormente (`com.c6bank.app`) instalada; app existente
`br.com.c6tag.diagnostico` com versionCode 1 no usuário principal 0.
Nenhum serial registrado neste relatório; seleção operacional é temporária e
externa ao repositório.

Uma consulta inicial de pacotes sem filtro de usuário retornou os pacotes
esperados, mas também aviso de acesso negado a perfil restrito. Limitada a
consulta ao usuário principal 0, sem tentar acessar o perfil restrito, ela
terminou sem aviso. Apenas metadados foram consultados.

Build/JVM/APKtest/lint reconfirmados com exit 0, sem connectedAndroidTest no
aparelho físico. APK e hash coincidem com a rodada revisada:
versão 1.1/código 2, 12.973.580 bytes, SHA-256
`1eaacb831de78a2fb60d628e5bcffeb0dd72fd808a8aedad1bd3f6fe5ccad1bf`.
BuildConfig exato e ausência de permissões proibidas conferidos novamente.
Atualização ainda não executada neste registro; leitura/foco do TalkBack
continuam pendentes de observação manual assistida.

### Atualização autorizada aplicada

Após verificar novamente o mesmo alvo físico, modelo e usuário principal 0,
`adb -s <alvo autorizado> install -r --user 0 app-debug.apk` retornou `Success`.
Metadados do pacote existente confirmaram versionCode 2 (antes 1).
Não houve desinstalação, limpeza de dados, instalação de APK de teste nem
concessão de permissões por comando.

Iniciado somente `br.com.c6tag.diagnostico/.MainActivity` no usuário principal:
`am start -W` retornou `Status: ok`, `LaunchState: COLD`, atividade esperada.
Isso comprova atualização/início aceitos pelo sistema, não tela vista, migração
conferida pelo usuário, captura completa, som ou alertas físicos.

Próxima observação assistida: usuário confere a interface e os registros antigos
em Ajustes → Diagnóstico técnico, sem limpar/exportar ou enviar conteúdo privado.
Depois, configurar referência local e conferir TalkBack e fluxos naturais.

Usuário respondeu “sim” à conferência dos registros antigos em Ajustes →
Diagnóstico técnico após a atualização. Evidência relatada de histórico anterior
acessível, sem extração, exportação ou contagem independente de dados privados.
Configuração do saldo de referência, avisos e TalkBack ainda pendentes.

Usuário esclareceu que já havia configurado a referência ao abrir o app, confirmou
que a estimativa aparece corretamente e relatou débitos reconhecidos de
notificações recebidas naturalmente nesse intervalo. Não orientar nova referência
desnecessária: ela substituiria o marco já configurado. Nenhum valor, conteúdo
bancário, comprovante ou credencial foi solicitado/coletado; não houve transação
exigida para o teste. Relato corrobora uso real de referência/captura/reconhecimento
de débitos, mas não comprova captura integral ou cálculo bancário auditado.
Permissão de avisos, ciência/pausa, recuperação e TalkBack seguem em conferência.

Na conferência separada da permissão de avisos, usuário confirmou que estão
permitidos e que já concedeu a permissão e fez a configuração manualmente.
Não foram executados comandos de permissão/configuração no aparelho.
Isso não comprova aviso efetivamente exibido, som, repetição ou ciência/pausa.
Próximo ensaio proposto: elevar temporariamente o limite, sem mudar referência
nem realizar transação, somente após consentimento específico; depois restaurar
o limite original. Esse ensaio ainda não foi autorizado nem observado.

Após a proposta de ensaio sem transação, usuário relatou: “Funcionou, ele
notificou corretamente”. Registrado primeiro aviso local confirmado por relato
no S23; não apenas uma solicitação de publicação aceita pelo sistema.
Não foram informados som, horário/latência, condição de tela ou valor do limite;
não inferir esses detalhes nem a restauração do limite original.
Ciência/pausa, retomada e encerramento do ciclo ainda não observados.

Após orientação para tocar em “Estou ciente · pausar por … min”, usuário respondeu
“sim” à conferência de “Lembretes pausados”, fim previsto da pausa e saldo igual.
Registrado estado visual de pausa e ausência de alteração do saldo por relato;
não houve espera/medição da supressão de lembretes ou do término automático.
Retomada manual e restauração do limite original seguem pendentes.

Após orientação para “Retomar lembretes”, usuário confirmou que o estado
“Lembretes pausados” desapareceu e voltou “Saldo abaixo do limite”.
Retomada visual confirmada por relato; não comprova prazo de próxima entrega
nem ausência de rajadas ao longo do tempo. Restauração do limite original
e encerramento do ciclo de teste ainda pendentes.

Usuário confirmou a restauração do limite de atenção original e o desaparecimento
de “Saldo abaixo do limite”, após orientação sem alterar a referência de saldo.
Registrada saída visual da condição de teste por restauração de configuração;
não confundir com recuperação por crédito natural, que continua não observada.
Primeiro aviso, estado de ciência/pausa, retomada e retorno do limite estão
confirmados por relato; prazos/supressão temporal e TalkBack seguem pendentes.

Usuário autorizou ativar temporariamente o TalkBack para checagem assistida de
leitura/foco. Ativação e desativação serão manuais, com orientação prévia dos
gestos; nenhuma configuração de acessibilidade foi alterada por comando.
Consentimento não é evidência de ativação nem de leitura/foco aprovados.

### Fechamento da checagem dirigida e da sessão física

Após orientação dos gestos de navegação, ativação/desativação manual e percurso
da Visão geral (saldo estimado, botões e abas), usuário informou: “Certo, testei
e esta funcional isso no talk back, botoes etc. Tudo certo”.
Registrada aprovação por relato da checagem dirigida de TalkBack. Não inferir
auditoria manual de todas as telas, temas ou fluxos de formulário a partir dela.
Nenhum conteúdo bancário ou valor lido foi solicitado. Desativação posterior do
TalkBack não foi confirmada; pode ser feita manualmente se usado só no ensaio.

**Tarefa 9 encerrada no escopo documentado:** evidência técnica anterior e
checagem manual dirigida complementares, sem substituir uma pela outra.
**Tarefa 10: sessão encerrada com resultado parcial**, sem exigir eventos
bancários ou ampliar consentimento para ensaios de interrupção.

Estabelecido por comando: atualização preservando a instalação, versionCode 2
e início da Activity. Confirmado por relato: registros anteriores acessíveis,
referência configurada/estimativa correta, débitos naturais reconhecidos,
avisos permitidos manualmente, primeiro aviso local, estados de ciência/pausa
com saldo inalterado, retomada, restauração do limite e TalkBack dirigido.

Limites/residuais: sem auditoria privada de aritmética ou captura integral,
crédito natural, deduplicação física exaustiva, medição de repetição/fim de pausa,
supressão temporal, som, segundo plano/tela bloqueada, reboot, revogação ou
force-stop. Limpeza/exportação e todos os demais cenários não observados do
checklist permanecem pendentes; não foram executados para completar a lista.
Restauração do limite não equivale a recuperação por crédito bancário.

Artefato instalado permanece versão 1.1/código 2, 12.973.580 bytes, SHA-256
`1eaacb831de78a2fb60d628e5bcffeb0dd72fd808a8aedad1bd3f6fe5ccad1bf`.
As alterações deste fechamento são somente documentais. Preservar a branch
`feat/monitor-history-and-settings`, sem merge/push ou novas ações físicas
automáticas; comunicação remota continua fora desta versão.

Verificação local deste fechamento documental: comando completo
JVM/build/APKtest/lint aceito com exit 0, `BUILD SUCCESSFUL`, 84 tarefas
(1 executada/83 up-to-date). Relatórios JVM em cache continuam com 112 testes,
zero falhas/erros/ignorados; lint 0 erros/fatais e 8 avisos.
Não houve nova execução instrumentada nem conexão ADB nesta etapa.
Tamanho e SHA-256 do APK conferidos e inalterados em relação ao instalado.
