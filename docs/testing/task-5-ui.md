# Tarefa 5 — interface e integração do diagnóstico

## Implementação

- `DiagnosticScreen` apresenta o estado em português, separando autorização do listener, conexão observada e configuração do pacote-alvo. Erros exibem mensagens seguras, sem texto bruto de exceções.
- O histórico mostra registros locais em ordem decrescente, permite carregar páginas anteriores e repetir uma consulta com falha. A limpeza exige confirmação; cancelar não apaga dados.
- `EventDetail` apresenta separadamente os campos persistidos, identifica valores ausentes e permite percorrer textos longos e linhas sem truncá-los.
- `DiagnosticViewModel` carrega o histórico inicial, acompanha novos IDs persistidos e serializa paginação/limpeza para não restaurar dados antigos após uma limpeza confirmada.
- `MainActivity` coleta estado com lifecycle, atualiza a autorização em `onResume` e abre as configurações somente após ação explícita. Não há pedido automático de acesso.
- Falha ao iniciar Settings é mantida em `settingsLaunchError`, separada de `authorization`; a UI mostra uma mensagem segura própria e preserva autorização, conexão e erros do gravador. `CHECK_FAILED` permanece reservado à falha real na consulta de acesso.
- Política de limpeza: somente o retorno bem-sucedido de `startActivity` limpa o erro anterior de abertura; uma nova falha o mantém/define. Nenhuma mensagem da exceção é guardada ou exibida.
- Os textos de contagem usam recurso plural do Android; detalhes são renderizados por composables com nomes convencionais.

## RED / GREEN

- RED da tela mínima: seis testes instrumentados executaram e falharam por conteúdo e ações ainda ausentes.
- RED da rolagem do detalhe: em uma rodada focada, 9/10 testes passaram; o teste restante verificou `1. Linha 1` depois de rolar somente até o título `Linhas`. A falha era a posição da viewport no teste, não a renderização do detalhe.
- GREEN focado: o teste passou a rolar até cada linha antes de verificar sua visibilidade; `DiagnosticScreenTest` passou com **10/10**.
- RED da regressão de Settings: `DiagnosticViewModelTest#settingsLaunchFailurePreservesAuthorizationAndHasSeparateError` executou e falhou por assertion: `expected:<AUTHORIZED> but was:<CHECK_FAILED>`.
- GREEN focado: `DiagnosticViewModelTest` passou **8/8**; `DiagnosticScreenTest` passou **11/11**. Cobertura inclui AUTHORIZED, NOT_AUTHORIZED e CHECK_PENDING, preservação de conexão/estado do gravador, mensagem específica e limpeza do erro somente após sucesso.
- Verificação JVM/build/APK instrumentado/lint: `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain` — BUILD SUCCESSFUL; **32 testes JVM**, 0 falhas/erros/ignorados; lint com **0 erros e 9 avisos** restantes.
- Verificação instrumentada completa: `:app:connectedDebugAndroidTest --console=plain` — BUILD SUCCESSFUL; **35 testes**, 0 falhas/erros/ignorados: 6 de banco, 7 de mapper, 3 de coordinator, 11 da tela e 8 do ViewModel.
- As execuções instrumentadas usaram somente o AVD `Medium_Phone_API_36.1`, serial `emulator-5562`, com `-read-only -no-snapshot-save -no-snapshot-load -no-window -no-audio`. O AVD foi encerrado; `adb devices -l` ficou sem dispositivos conectados.

Comandos usados para a regressão e GREEN focado:

```powershell
powershell.exe -NoProfile -Command '$env:ANDROID_SERIAL="emulator-5562"; & ".\tools\gradle.ps1" ":app:connectedDebugAndroidTest" "-Pandroid.testInstrumentationRunnerArguments.class=br.com.c6tag.diagnostico.ui.DiagnosticViewModelTest#settingsLaunchFailurePreservesAuthorizationAndHasSeparateError" "--console=plain"'
powershell.exe -NoProfile -Command '$env:ANDROID_SERIAL="emulator-5562"; & ".\tools\gradle.ps1" ":app:connectedDebugAndroidTest" "-Pandroid.testInstrumentationRunnerArguments.class=br.com.c6tag.diagnostico.ui.DiagnosticViewModelTest" "--console=plain"'
powershell.exe -NoProfile -Command '$env:ANDROID_SERIAL="emulator-5562"; & ".\tools\gradle.ps1" ":app:connectedDebugAndroidTest" "-Pandroid.testInstrumentationRunnerArguments.class=br.com.c6tag.diagnostico.ui.DiagnosticScreenTest" "--console=plain"'
```

Comandos da verificação final:

```powershell
powershell.exe -NoProfile -File tools/gradle.ps1 :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
powershell.exe -NoProfile -Command '$env:ANDROID_SERIAL="emulator-5562"; & ".\tools\gradle.ps1" ":app:connectedDebugAndroidTest" "--console=plain"'
```

## Limites

Os testes de UI e ViewModel usam estado controlado/fakes. Os testes de captura usam fixtures sintéticas e não publicam notificações no sistema. O pacote-alvo continua vazio por padrão; não foi concedido nem alterado acesso do listener, e nenhum aparelho físico foi usado.

Esta tarefa não adiciona exportação, backup XML, permissões ou lógica financeira. Os avisos de lint relativos a `allowBackup` e ícone não foram tratados, pois essas alterações estão fora do escopo.
