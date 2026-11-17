# Tarefa 6 — exportação explícita e exclusão de backup

## Implementação e decisões

- `DiagnosticExporter` escreve um documento JSON UTF-8 (`schemaVersion=1`)
  diretamente da sequência de snapshot do `DiagnosticRecorder`. Metadados do
  documento e versão do app são distintos da versão do codec SQLite.
- O exportador escreve um evento por vez, em ordem decrescente de ID local.
  Textos, linhas, metadados, timestamps, remoção e interpretação permanecem
  separados; os valores de centavos são inteiros JSON `Long`, sem conversão
  por ponto flutuante. O documento não lê bundles, imagens, intents ou erros
  brutos.
- A função que abre o destino só é chamada dentro do callback serial de
  `recorder.exportSnapshot`. O `Writer` fecha o stream antes de o callback
  retornar e de o cursor/transação terminarem. A Activity fornece somente um
  `ContentResolver` de application e a `Uri` imutável; não é retida pelo
  trabalho de disco. Depois que o comando de snapshot foi aceito, cancelar a
  coroutine chamadora cancela seu `await`, não o comando já executado pelo
  worker: ele termina a escrita e fecha o stream no próprio worker antes de
  liberar cursor/transação. O chamador cancelado não recebe `Success`; o
  ViewModel propaga `CancellationException` sem publicar sucesso na UI.
- A tela apresenta aviso sensível cancelável antes do seletor SAF
  `CreateDocument("application/json")`. O cancelamento do seletor não chama o
  exportador nem exibe sucesso/erro. Abrir stream nulo, lançar exceção, falhar
  escrita/fechamento ou não conseguir iniciar o seletor produz mensagem
  segura; não se exibe `exception.message`. Uma falha de escrita pode deixar
  arquivo parcial no destino, que o app não tenta apagar.
- Limpeza e exportação usam o mutex da UI e a fila FIFO do recorder; **Limpar
  registros** fica desabilitado enquanto a exportação está ocupada. Eventos
  recebidos depois do início do snapshot ficam fora daquele arquivo e
  permanecem no banco. Uma exportação lenta ocupa a fila serial e pode atrasar
  append, paginação e limpeza; não foi criado backend ou fila paralela.
- `allowBackup=false` foi mantido. As regras explícitas excluem todos os
  domínios privados no formato legado e nas seções de backup em nuvem e
  transferência entre dispositivos. A exportação manual não é apagada pela
  limpeza interna. As regras padrão não protegem contra root, capturas de tela,
  exportação manual ou política OEM fora do mecanismo padrão do Android.
  Os domínios foram conferidos na documentação oficial de
  [Auto Backup](https://developer.android.com/guide/topics/data/autobackup).

## RED / GREEN e verificação

- RED original do exportador com stub mínimo: no AVD, **2/3** testes falharam por
  `schemaVersion` e `events` ausentes; a falha foi de serialização, não de
  compilação. Na rodada original de implementação, `DiagnosticExporterTest`
  cobriu **4/4**, incluindo
  vazio, eventos duplicados com IDs locais distintos, ordem/metadados de
  snapshot, Unicode/nulos/valores extremos, `Long.MAX_VALUE` em centavos,
  abertura em worker IO, stream nulo, falha ao abrir/escrever/fechar e append
  enfileirado após o snapshot.
- RED de privacidade: **1/2** testes instrumentados falhou porque o recurso
  legado de backup ainda não estava empacotado. GREEN final: `ManifestPrivacyTest`
  **2/2**, lendo PackageInfo e os recursos XML compilados.
- RED da UI: o teste instrumentado não encontrou o botão de exportação antes
  da implementação. GREEN final: `DiagnosticScreenTest` **13/13**; warning,
  cancelar/confirmar por callback, mensagens seguras e bloqueio dos botões
  durante exportação.
- O primeiro RED do ViewModel foi de compilação porque a API de exportação
  ainda não existia. GREEN final: `DiagnosticViewModelTest` **11/11**,
  incluindo cancelamento de destino sem exportação, sucesso, provider nulo,
  falha de stream e falha ao iniciar seletor preservando autorização/conexão.
- Suíte JVM: `:app:testDebugUnitTest` — **32 testes**, sem falhas/erros.
- Suíte instrumentada original: `:app:connectedDebugAndroidTest` — **46
  testes**, sem falhas/erros/ignorados: 6 banco, 4 exportador, 7 mapper,
  3 coordinator, 2 privacidade do manifest, 13 tela e 11 ViewModel.
- Reforço de cobertura, sem RED novo e sem defeito de produção observado:
  `cancellingCallerDoesNotCloseOutputWhileAcceptedSnapshotIsWriting` usa um
  chamador em scope separado do recorder, bloqueia deterministicamente uma
  escrita no stream aberto, cancela o chamador e verifica que o stream continua
  aberto enquanto o worker escreve. Após liberar a latch, uma leitura enfileirada
  serve de barreira real atrás do snapshot; o teste confirma fechamento
  exatamente uma vez, JSON completo, leitura e limpeza subsequentes no banco.
  As latches têm timeout e são liberadas em `finally`; não há `sleep`, polling
  nem hook de produção. A execução focada passou **5/5**.
- Após o reforço de cancelamento, a suíte instrumentada completa passou
  **47/47**, sem falhas/erros/ignorados: 6 banco, 5 exportador, 7 mapper,
  3 coordinator, 2 privacidade do manifest, 13 tela e 11 ViewModel. O XML
  `app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_36.1(AVD) - 16-_app-.xml`
  registra `tests=47`, `failures=0`, `errors=0`, `skipped=0`.
- JVM e APKs foram verificados com `--rerun-tasks`: `:app:testDebugUnitTest`
  passou **32/32**, `:app:assembleDebug` e `:app:assembleDebugAndroidTest`
  concluíram com sucesso.
- Lint foi executado por último com `:app:lintDebug --rerun-tasks`:
  **0 erros, 8 avisos**. O XML
  `app/build/reports/lint-results-debug.xml` foi regenerado em
  `2026-11-16T23:02:06.7095713Z` (UTC) e registra 8 issues, todos warnings.
  Eles são versões mais novas disponíveis de ferramentas/dependências e ícone
  ausente; o aviso anterior de `allowBackup` desapareceu e versões fixadas não
  foram elevadas nesta tarefa.
- AAPT2 no APK final confirmou `allowBackup=false`,
  `fullBackupContent=@xml/backup_rules` e
  `dataExtractionRules=@xml/data_extraction_rules`. `aapt2 dump permissions`
  mostrou somente a permissão signature interna
  `br.com.c6tag.diagnostico.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`; não há
  `INTERNET`, `POST_NOTIFICATIONS` ou permissões de armazenamento.
- A execução focada atualizada do exportador pode ser repetida com:

  ```powershell
  powershell.exe -NoProfile -ExecutionPolicy Bypass -Command '$env:ANDROID_SERIAL="emulator-5580"; & ".\tools\gradle.ps1" ":app:connectedDebugAndroidTest" "-Pandroid.testInstrumentationRunnerArguments.class=br.com.c6tag.diagnostico.diagnostic.DiagnosticExporterTest" "--console=plain"'
  ```

  O RED inicial executou com o AVD próprio como único dispositivo conectado;
  a primeira chamada iniciada pelo WSL teve erro de quoting ao definir
  `ANDROID_SERIAL`. As execuções GREEN e a suíte final foram repetidas com
  `ANDROID_SERIAL=emulator-5580` explícito.
- Testes instrumentados usaram somente o AVD `Medium_Phone_API_36.1`, serial
  `emulator-5580`, iniciado com `-read-only -no-snapshot-save
  -no-snapshot-load -no-window -no-audio`, em porta livre. O AVD criado foi
  encerrado com `tools/adb.ps1 -s emulator-5580 emu kill`. Nenhum aparelho
  físico foi usado; `adb devices -l` ficou sem dispositivos conectados. Não foi
  concedido acesso ao listener nem publicada notificação bancária.

Comandos finais executados no Windows:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command '$env:ANDROID_SERIAL="emulator-5580"; & ".\tools\gradle.ps1" ":app:connectedDebugAndroidTest" "-Pandroid.testInstrumentationRunnerArguments.class=br.com.c6tag.diagnostico.diagnostic.DiagnosticExporterTest" "--console=plain"'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks --console=plain
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command '$env:ANDROID_SERIAL="emulator-5580"; & ".\tools\gradle.ps1" ":app:connectedDebugAndroidTest" "--console=plain"'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:lintDebug --rerun-tasks --console=plain
```

## Limites

Esta tarefa comprova compilação, regras de backup empacotadas e comportamento
com fixtures sintéticas; **não comprova captura real do C6**. O pacote-alvo
continua vazio por padrão e autorização/coleta real não foram testadas.
