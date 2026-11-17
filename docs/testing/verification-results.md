# Evidências de verificação

## Estado em 2026-11-16

Implementação do coletor concluída e aprovada na revisão final para diagnóstico
assistido. Aplicativo configurado para `com.c6bank.app` e instalado com autorização
no S23 SM-S911B, Android 17/API 37. Usuário relatou 8 avisos de tags, 2 propagandas
sem valor e um crédito Pix de R$ 1,00 reconhecido com atualização da lista.

Verificações locais: 32 testes JVM, 47 instrumentados no emulador e lint com
0 erros/8 avisos. Build/JVM/APKtest/lint reconfirmados após a revisão final.
Validação física ainda parcial; saldo e alertas não implementados.
As seções seguintes registram a evolução cronológica, inclusive estados pendentes
que foram resolvidos nas etapas posteriores.

### Scaffold — verificação independente

Comando:

```sh
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:assembleDebug :app:lintDebug :app:testDebugUnitTest --console=plain
```

Resultado: exit 0, BUILD SUCCESSFUL in 1s, 50 tarefas acionáveis (1 executada,
49 atualizadas). `testDebugUnitTest` retornou NO-SOURCE, não testes aprovados.
APK-base: `app/build/outputs/apk/debug/app-debug.apk`.
Lint: 0 erros e 9 avisos, conforme relatório da Tarefa 1.
Revisões de escopo e qualidade aprovadas. Build/lint reconfirmados depois das
revisões: exit 0, BUILD SUCCESSFUL in 1s, 48 tarefas (1 executada, 47 atualizadas).
Observação menor: checagem preventiva do wrapper ADB cobre apenas servidor padrão;
usar somente ADB local neste experimento.

### Interpretação — verificação independente

```sh
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain
```

Exit 0, BUILD SUCCESSFUL in 1s, 53 tarefas (1 executada, 52 atualizadas).
XML `app/build/test-results/testDebugUnitTest/TEST-br.com.c6tag.diagnostico.diagnostic.NotificationClassifierTest.xml`
confirma 19 testes, 0 falhas, 0 erros, 0 ignorados. Este resultado estabelece a
interpretação de fixtures sanitizadas, não a leitura real das notificações do C6.
Revisões de escopo e qualidade da Tarefa 2 aprovadas. O revisor de qualidade também
executou testDebugUnitTest com --rerun-tasks, confirmando os mesmos 19 testes.

### Histórico e recorder — Tarefa 3

Controller executou novamente:

```sh
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

Exit 0, BUILD SUCCESSFUL in 1s, 84 tarefas (1 executada, 83 atualizadas).
XMLs conferidos: 28 testes JVM (19 classificador + 9 recorder), 0 falhas,
0 erros, 0 ignorados. Compilação do APK instrumentado também aprovada.

Implementador executou SQLite por `adb ... am instrument` em AVD read-only
temporário: 5 testes, `OK (5 tests)`. Comandos e resumo sanitizado persistidos em
`docs/testing/task-3-storage.md`. Esta execução direta não produziu XML Gradle;
não confundir o resumo documentado com relatório XML ou execução no S23.
Emulador encerrado após uso; nenhum aparelho físico usado.
Revisão de escopo da Tarefa 3 solicitou cobertura explícita de mesma chave e mesmo
valor interpretado preservados em duas gravações independentes. Ajuste restrito
a testes e documentação concluído, sem alteração de produção. Nova verificação
independente de build/JVM/compilação instrumentados/lint: exit 0, 84 tarefas.
Implementador registrou a rodada ampliada: 29 testes JVM e 6 SQLite no emulador
read-only, com comandos e saída sanitizada na documentação da Tarefa 3.
Nova revisão de escopo e revisão de qualidade aprovadas.

### Captura — Tarefa 4

Verificação independente: `:app:testDebugUnitTest :app:assembleDebug
:app:assembleDebugAndroidTest :app:lintDebug --console=plain`, via script Windows.
Exit 0, BUILD SUCCESSFUL, 84 tarefas. XMLs conferidos pelo controller: 32 JVM
(19 classificador + 10 recorder + 3 estado de captura) e 16 instrumentados
(6 SQLite + 7 mapper + 3 coordinator), todos sem falhas/erros/ignorados.
O XML instrumentado está em `app/build/outputs/androidTest-results/connected/debug/`.
A rodada no emulador foi executada pelo implementador, sem conceder acesso de
listener. Dados são sintéticos; não comprovam binding nem leitura real do C6.
Revisões de escopo e qualidade aprovadas; nenhum bloqueio identificado.

### Interface — Tarefa 5

Verificação independente pelo controller: mesmo comando completo de build/JVM/
compilação instrumentada/lint das etapas anteriores, exit 0, 84 tarefas.
XMLs conferidos: 32 JVM e 32 instrumentados, 0 falhas/erros/ignorados.
Os 16 testes novos são 10 de tela e 6 de ViewModel. Execução instrumentada
no emulador pelo implementador, sem tocar aparelho físico ou acesso do listener.
Evidência: `docs/testing/task-5-ui.md`. Revisão de escopo encontrou erro de estado:
falha ao abrir configurações confundida com falha de checagem de autorização.
Correção implementada: erro de abertura separado, autorização preservada,
checagem real continua usando CHECK_FAILED. RED reproduziu expected AUTHORIZED
versus CHECK_FAILED. Implementador reportou 32 JVM e 35 instrumentados verdes.
Controller refez build/JVM/compilação instrumentados/lint com exit 0.
Nova revisão de escopo e revisão de qualidade aprovadas. A autoatualização volta
à primeira página; registros anteriores permanecem no banco, acessíveis ao paginar.

### Exportação e privacidade — Tarefa 6

Controller reconfirmou build/JVM/compilação de APK instrumentado/lint: exit 0,
84 tarefas. XMLs conferidos: 32 JVM e 46 instrumentados, todos sem falhas/erros/
ignorados. Lint 0 erros e 8 avisos, conforme relatório do implementador.
O AVD read-only usado foi encerrado; nenhum aparelho físico ou autorização
do listener alterado. Evidência: `docs/testing/task-6-export.md`.
Revisão de escopo pediu teste explícito de cancelamento enquanto o stream está
aberto e lint forçado para comprovar versão final. Reforço concluído sem alteração
de produção: worker termina comando já aceito e fecha o stream exatamente uma vez,
sem fechamento concorrente pelo chamador cancelado. Implementador reportou 32 JVM
e 47 instrumentados, lint forçado 0 erros/8 avisos com relatório atualizado.
Controller refez o comando completo de build/JVM/APKtest/lint com exit 0.
Nova revisão de escopo e revisão de qualidade aprovadas. Próxima etapa:
identificação e teste assistido no S23, aguardando conexão/autorização USB.

### Preparação S23 — Tarefa 7

Fallback de configuração `-Pc6PackageName` > `local.properties:c6.packageName` >
vazio e roteiro físico implementados. Revisão de escopo aprovada; revisão de
qualidade encontrou ambiguidade: providers.gradleProperty também lê propriedades
herdadas, embora o contrato especifique -P explícito. Ajuste e verificação
isolada concluídos: leitura restrita a startParameter.projectProperties,
ignorando fontes herdadas não explícitas. Controller reconfirmou build completo/
JVM/APKtest/lint (modo --quiet, sem erros). Nova revisão de escopo aprovada,
revisão de qualidade aprovada. Preparação concluída; identificação, configuração
do pacote, instalação e teste no S23 permanecem pendentes da sessão autorizada.
Controller reconfirmou build/JVM/APK de teste/lint com exit 0. Nenhuma instalação
ou leitura física executada; alvo padrão vazio, sem local.properties criado.
Os 47 instrumentados são evidência anterior, não reexecutada nesta preparação.

### S23 — consultas iniciais autorizadas

Após o usuário informar “conectado”, ADB encontrou exatamente um dispositivo
autorizado. Consultas direcionadas de getprop confirmaram SM-S911B, Android 17,
API 37. `pm list packages c6` retornou apenas `com.c6bank.app`.
Não foi persistido serial nem consultado conteúdo de notificações. Nenhum APK
instalado, alvo configurado ou acesso especial concedido. Aguardando confirmação
do pacote e autorização de instalação.

### S23 — instalação autorizada

Usuário confirmou `com.c6bank.app` e autorizou instalação. `local.properties`
criado com essa única configuração, sem dados bancários. Recompilado com
`-Pc6PackageName=com.c6bank.app` e comando completo JVM/build/APKtest/lint,
sem erros. BuildConfig do app conferido antes da instalação.

APK: `app/build/outputs/apk/debug/app-debug.apk`, 11.635.770 bytes;
SHA-256 `90a1f89ceaf72ee8015f3cdbfbf1aa9ba178d0db4cd1c90b23e434c96d3b45ce`.
`adb install -r` direcionado ao S23 confirmado retornou `Success`.
`am start` iniciou somente `br.com.c6tag.diagnostico/.MainActivity`.
Nenhuma permissão de listener concedida por comando e nenhuma leitura de
notificações realizada. Aguardando confirmação visual e consentimento manual.

### S23 — primeira observação local

Após a orientação de acesso manual, usuário informou: “apareceu várias notificações
na lista”. Isso é evidência relatada de recebimento/exibição real, não prova de
captura completa ou de interpretação correta. Tipo snapshot/callback, valores,
classificação e comportamento com novos avisos ainda serão conferidos. Nenhum
conteúdo bancário foi extraído por ADB.

Usuário conferiu o conjunto exibido: 8 notificações de tags e 2 propagandas do
C6, estas sem valor reconhecido. Isso corrobora a captura por pacote e permite
excluir formatos não reconhecidos de uma futura contabilização. Não equivale a
validação financeira de cada lançamento. Não houve alteração para apagar registros:
o coletor mantém evidências desconhecidas. Próximo teste: novo aviso natural com
aplicativo em segundo plano, distinto do snapshot inicial.

### S23 — crédito novo

Por iniciativa própria, usuário propôs teste manual de Pix de R$ 1,00. Orientado
a deixar o diagnóstico em segundo plano, aguardar o aviso do C6 e conferir
crédito/tipo, respondeu que reconheceu corretamente e atualizou a lista.
É evidência relatada de interpretação e atualização com crédito real; não houve
consulta de comprovante, automação bancária nem extração de conteúdo por ADB.
Débito novo, tela bloqueada, reinicialização, reconexão e repetições reais ainda
pendentes. Saldo não implementado nem validado. Revisão final do código aprovada,
sem defeitos críticos ou importantes. Resumos de documentação atualizados após
observações menores do revisor; nenhuma alteração de produção nessa rodada.

### Ambiente confirmado

- Android Studio instalado no Windows.
- Android SDK local contém plataformas API 33, 34, 35 e 36.
- Build-tools locais: 35.0.0, 36.0.0 e 36.1.0.
- Ambiente WSL não tem Java, Gradle ou ADB no PATH.
- Gradle 9.1.0 executou pelo Windows com JDK 21.0.9; exit code 0.

Comando executado a partir do WSL:

```sh
powershell.exe -NoProfile -Command '$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"; & "C:\Users\Victor\.gradle\wrapper\dists\gradle-9.1.0-bin\9agqghryom9wkf8r80qlhnts3\gradle-9.1.0\bin\gradle.bat" --version'
```

Resultado relevante:

```text
Gradle 9.1.0
Launcher JVM: 21.0.9 (Oracle Corporation 21.0.9+7-LTS-338)
Daemon JVM: C:\Program Files\Java\jdk-21
OS: Windows 11 10.0 amd64
```

### Confirmado neste ciclo

- Modelo, versão/API e autorização ADB: SM-S911B, Android 17/API 37.
- Pacote C6 identificado e confirmado: `com.c6bank.app`.
- Compatibilidade de build, APK instalado e abertura do diagnóstico.
- Captura inicial e crédito novo reconhecidos, segundo conferência do usuário.
- Revisões de escopo e qualidade de cada etapa e revisão final aprovadas.

### Ainda pendente ou limitado

- Débito novo natural e conferência individual de valores/datas.
- Diferenciação real entre atualização, repetição, snapshot e nova cobrança.
- Tela bloqueada, reconexão, reinicialização, force-stop e restrições Samsung.
- Exportação/cancelamento/limpeza no aparelho físico.
- Reconciliação manual com o saldo bancário e comportamento em uso prolongado.
- Captura integral não é garantida pelo Android ou pelo banco.

Não inferir saldo correto ou confiabilidade operacional a partir dos testes
sintéticos ou das primeiras notificações observadas.
