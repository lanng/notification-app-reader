# Tarefa 7 — preparação para diagnóstico físico

## Preparação concluída

- Criado `docs/testing/s23-diagnostic-checklist.md` com matriz de cenário, data,
  pré-condições, ação, resultado esperado/observado, status e limites. Todos os
  cenários físicos permanecem explicitamente **PENDENTES**. A execução não exige
  nem autoriza iniciar transações; casos condicionados a eventos naturais ficam
  pendentes se esses eventos não ocorrerem.
- Atualizado `README.md` com consultas ADB limitadas a metadados, seleção
  explícita `-s SERIAL` quando necessária e pré-condição de autorização explícita
  do usuário para USB e consulta de metadados antes de qualquer comando ADB.
  Instalação e acesso especial também exigem autorização; documentados
  confirmação do pacote, exportação/limpeza e limites do diagnóstico.
- `app/build.gradle.kts` seleciona primeiro somente
  `gradle.startParameter.projectProperties["c6PackageName"]` (propriedades `-P`
  da invocação, inclusive vazio explícito), depois `c6.packageName` em
  `local.properties` lido como UTF-8 por `Properties.load(Reader)`, fechando
  reader e stream com `use`, e por fim vazio. Arquivo ausente ou chave ausente
  resulta em vazio. O escape de literal Java e `BuildConfig.C6_PACKAGE_NAME`
  foram preservados; nenhuma validação de formato ou mudança no filtro exato foi
  introduzida.
- `local.properties` não existia no projeto e não foi criado nem alterado. Não
  foi configurado pacote-alvo real; a configuração vazia desativa a captura.

## Reprodução da precedência e correção

- **RED antes da correção:** uma fixture Gradle isolada usou o seletor então
  presente em `app/build.gradle.kts`, `providers.gradleProperty`, com alvo local
  sintético `org.synthetic.local`. Sem `-P`, os resultados gerados foram:
  - variável `ORG_GRADLE_PROJECT_c6PackageName=org.synthetic.env` no processo
  PowerShell filho → `org.synthetic.env`;
  - `-Dorg.gradle.project.c6PackageName=org.synthetic.system` →
    `org.synthetic.system`;
  - `gradle.properties` em um `GRADLE_USER_HOME` temporário e isolado,
    `c6PackageName=org.synthetic.global` → `org.synthetic.global`.
- A fixture executou `:writeC6Selection --rerun-tasks --no-daemon`. ENV foi
  definida somente no processo PowerShell filho; system property foi passada
  como `-Dorg.gradle.project.c6PackageName=org.synthetic.system`; a propriedade
  global sintética existia somente no `gradle.properties` do user home
  temporário. Cada caso foi executado sem `-P`, com a configuração local
  sintética conflitante.
- **Causa:** `providers.gradleProperty` agrega propriedades de projeto
  provenientes também de ambiente, propriedades de sistema e `gradle.properties`;
  portanto a origem herdada podia preceder o fallback local apesar de o contrato
  permitir apenas `-P`, `local.properties` e vazio.
- **Correção / GREEN:** a leitura passou a usar apenas
  `gradle.startParameter.projectProperties["c6PackageName"]`. A fixture foi
  regenerada a partir do seletor atualizado e produziu:
  - sem `-P`, cada uma das três fontes herdadas (ENV, system property e
    `gradle.properties`) foi ignorada; resultado `org.synthetic.local`;
  - `-Pc6PackageName=org.synthetic.cli`, mesmo com ENV e system property
    conflitantes e propriedade global sintética, resultou em
    `org.synthetic.cli`;
  - `-Pc6PackageName=` com as mesmas fontes conflitantes resultou em vazio;
  - sem `local.properties`, o valor global sintético foi ignorado e o resultado
    foi vazio.
- O harness e as configurações foram temporários, isolados em `app/build` e num
  `GRADLE_USER_HOME` temporário; foram removidos após os testes. A variável ENV
  existiu somente no processo PowerShell filho. Não foram lidos nem alterados
  `local.properties` do projeto, `gradle.properties` global do usuário,
  variáveis de ambiente User/Machine ou configurações globais.

## Verificação de configuração e build

- Estado sem propriedade e sem `local.properties` de alvo: executada a
  verificação completa sem `-P`; `BuildConfig.java` final contém
  `C6_PACKAGE_NAME = ""`.
- Prioridade no APK: executado
  `:app:assembleDebug -Pc6PackageName=com.example.synthetic --rerun-tasks
  --console=plain`; a compilação gerou exatamente
  `C6_PACKAGE_NAME = "com.example.synthetic"`. O alvo sintético foi removido
  pelo build final sem configuração.
- Comando final, sem configuração de pacote:

  ```powershell
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain --rerun-tasks
  ```

  Resultado: `BUILD SUCCESSFUL`; 84 tarefas executadas. Suíte JVM **32/32**,
  sem falhas, erros ou ignorados. `assembleDebug` e
  `assembleDebugAndroidTest` concluíram. Lint: **0 erros, 8 avisos**. APK de
  depuração gerado em `app/build/outputs/apk/debug/app-debug.apk`; não instalado.
  Os APKs foram gerados em `app/build/outputs/apk/debug/app-debug.apk` e
  `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`; ambos
  permaneceram sem instalação. Os `BuildConfig.java` de `debug` e
  `androidTest/debug` foram conferidos com `C6_PACKAGE_NAME = ""`; o projeto
  continua sem `local.properties`.
- Os **47/47** testes instrumentados registrados em
  `docs/testing/task-6-export.md` são evidência anterior, não foram reexecutados
  nesta preparação. Nenhum emulador foi iniciado.

## Limites e pendências físicas

Não houve consulta ADB, conexão/consulta ao telefone, instalação, concessão ou
revogação de acesso especial, abertura do C6, publicação de notificação ou
inspeção de dados bancários. Nenhuma versão/API/modelo do aparelho foi
confirmada nesta etapa. A identificação e confirmação do pacote real, a
configuração do alvo e todos os cenários da checklist aguardam a sessão
assistida e autorização do usuário. Os resultados de compilação e fixtures
sintéticas não validam captura física nem comportamento do Android/OEM.

Não foram adicionadas dependências nem alterado o SDK configurado. Este
documento e a checklist não atualizam `docs/testing/verification-results.md`,
o plano de descoberta ou as especificações globais.
