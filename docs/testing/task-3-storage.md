# Armazenamento diagnóstico (Tarefa 3)

O histórico fica no banco SQLite privado do aplicativo e registra observações
de diagnóstico, não um razão de transações. O ID SQLite é somente a identidade
do registro local: não identifica uma transação e não implica débito, crédito
ou reversão. Eventos recebidos repetidamente, snapshots e remoções são mantidos
como observações independentes.

O histórico não tem rotação por idade, limite automático ou descarte. Ele cresce
até uma limpeza explícita; portanto, use a captura apenas pelo tempo necessário
à investigação e limpe o histórico depois. O conteúdo de notificações pode ser
sensível, e o uso prolongado pode consumir armazenamento. Uma limpeza futura do
banco não apagará arquivos exportados separadamente.

O recorder serializa operações em uma fila sem limite fixo para não descartar
eventos silenciosamente. Se a produção exceder a velocidade do disco, a fila
pode crescer e consumir memória. Falhas são expostas em estado seguro, sem
mensagens de exceção ou conteúdo da notificação.

O payload SQLite é JSON interno versionado; ele não é o formato de um documento
de exportação. O snapshot fornece metadados e uma sequência válida somente
durante o callback, sob uma transação, para um futuro exportador com leitura
streaming.

## Evidências de teste

### RED/GREEN da implementação inicial

Na Tarefa 3 inicial, antes de existir a implementação, estes comandos falharam
na compilação por ausência das APIs que os testes descreviam:

```text
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:testDebugUnitTest --tests br.com.c6tag.diagnostico.diagnostic.DiagnosticRecorderTest --console=plain
```

Resultado RED: `:app:compileDebugUnitTestKotlin` falhou com referências ainda
inexistentes a `DiagnosticRecorder`, `DiagnosticStore` e
`StoredDiagnosticEvent`.

```text
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:assembleDebugAndroidTest --console=plain
```

Resultado RED: `:app:compileDebugAndroidTestKotlin` falhou com a referência
ainda inexistente a `DiagnosticDatabase` e tipos associados.

Depois da implementação inicial, a verificação completa foi executada em
2026-11-16:

```text
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

Resultado GREEN naquela execução: `BUILD SUCCESSFUL` (84 ações; 16 executadas,
68 em cache), com 28 testes JVM.

### Cobertura adicional da revisão

A revisão de conformidade pediu cobertura explícita de eventos repetidos com a
mesma chave e interpretação `TAG_DEBIT` de `1691` centavos. Os testes JVM e
SQLite agora preservam tanto a gravação do mesmo objeto duas vezes quanto duas
observações com `observedAtMs` distintos. Os testes conferem IDs distintos,
ordem, chave, tipo de interpretação e valor. A produção não foi alterada; esta
rodada expande cobertura de regressão e não é um novo RED de comportamento
ausente.

A verificação completa foi repetida em 2026-11-16:

```text
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

Resultado: `BUILD SUCCESSFUL` (84 ações; 12 executadas, 72 em cache). O XML
registrou **29 testes JVM, 0 falhas, 0 erros**:

- `app/build/test-results/testDebugUnitTest/TEST-br.com.c6tag.diagnostico.diagnostic.NotificationClassifierTest.xml`: 19 testes, 0 falhas — suíte anterior preservada.
- `app/build/test-results/testDebugUnitTest/TEST-br.com.c6tag.diagnostico.diagnostic.DiagnosticRecorderTest.xml`: 10 testes, 0 falhas.

### SQLite instrumentado em runtime

Os testes instrumentados foram executados diretamente por ADB, **não** por
`connectedAndroidTest`. O AVD usado foi `Medium_Phone_API_36.1`, com imagem
`android-36.1/google_apis_playstore/x86_64` (emulador Android 36.1). Nenhum
aparelho físico foi usado; os comandos ADB abaixo selecionam explicitamente
`emulator-5584`. A listagem ADB anterior ao início do AVD não mostrou nenhum
dispositivo conectado:

```text
/mnt/c/Users/Victor/AppData/Local/Android/Sdk/platform-tools/adb.exe devices -l
List of devices attached
```

Inicialização read-only do AVD:

```text
/mnt/c/Users/Victor/AppData/Local/Android/Sdk/emulator/emulator.exe -avd Medium_Phone_API_36.1 -port 5584 -read-only -no-snapshot-save -no-snapshot-load -no-window -no-audio
/mnt/c/Users/Victor/AppData/Local/Android/Sdk/platform-tools/adb.exe -s emulator-5584 wait-for-device
```

Instalação dos APKs construídos e execução:

```text
/mnt/c/Users/Victor/AppData/Local/Android/Sdk/platform-tools/adb.exe -s emulator-5584 install -r app/build/outputs/apk/debug/app-debug.apk
/mnt/c/Users/Victor/AppData/Local/Android/Sdk/platform-tools/adb.exe -s emulator-5584 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
/mnt/c/Users/Victor/AppData/Local/Android/Sdk/platform-tools/adb.exe -s emulator-5584 shell am instrument -w -r br.com.c6tag.diagnostico.test/androidx.test.runner.AndroidJUnitRunner
```

Resultado observado: `numtests=6`, `Time: 3.002`, `OK (6 tests)`. Os seis
casos executados foram:

- `nullableUnicodeAndAllPayloadFieldsRoundTripWithoutMixingMetadata`
- `paginationUsesDescendingExclusiveCursorValidatesLimitsAndClearKeepsIdsMonotonic`
- `unsupportedSchemaAndPayloadVersionsFailInsteadOfBeingTreatedAsEmpty`
- `sameKeyAndInterpretationAtDifferentObservationTimesSurviveReopen`
- `exportSnapshotReportsEmptyAndOrderedMetadataAndClosesResourcesAfterCallbackFailure`
- `duplicateKeysGetIndependentIdsAndEventsSurviveReopen`

Resumo sanitizado da saída instrumentada:

```text
INSTRUMENTATION_STATUS: numtests=6
INSTRUMENTATION_STATUS_CODE: 0  # para cada um dos seis casos
Time: 3.002
OK (6 tests)
```

O processo foi encerrado no mesmo emulador:

```text
/mnt/c/Users/Victor/AppData/Local/Android/Sdk/platform-tools/adb.exe -s emulator-5584 emu kill
```

Resultado observado: `OK: killing emulator, bye bye` e `OK`; o processo do
emulador terminou. A execução usou `-read-only -no-snapshot-save
-no-snapshot-load`. O log de encerramento informou `Not saving state: RAM not
mapped as shared`; esta documentação registra as flags e a saída observada, sem
inferir além disso sobre o estado interno do AVD.

Esta execução via `am instrument` não gera o XML de resultados do Gradle.
Não foi encontrado XML `DiagnosticDatabaseTest*.xml` em
`app/build/outputs/androidTest-results` nem em outro caminho do workspace. O
resumo acima é a evidência persistida da saída desta rodada instrumentada; não
foi criado ou simulado XML. A saída integral permaneceu no registro da sessão de
execução.
