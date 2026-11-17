# C6 Tag Diagnóstico

Aplicativo Android de diagnóstico com estado do listener e histórico local
paginado de observações. **A tela não calcula saldo**; classificações de
notificação são apenas candidatas experimentais, e a captura pode estar
incompleta. O app não gera alertas e não se conecta à internet.

## Ferramentas

- Windows com JDK 21 e Android SDK (API 36 e Build Tools 36.0.0).
- O script `tools/gradle.ps1` localiza o JDK 21 e o SDK Windows; caminhos
  configurados em `JAVA_HOME`, `ANDROID_SDK_ROOT` ou `ANDROID_HOME` são usados
  quando válidos. O script não instala ferramentas.
- Gradle Wrapper 9.1.0. As versões de plugins e bibliotecas estão fixadas em
  `gradle/libs.versions.toml`.
- Não é necessário instalar Gradle globalmente.

## Comandos no Windows

Execute na raiz do projeto:

**Pré-condição para qualquer comando ADB abaixo:** o usuário deve autorizar
explicitamente a conexão USB e a consulta de metadados. Os exemplos documentam
comandos possíveis; não autorizam conexão, consulta ou ação automaticamente.
Sem essa autorização, não os execute.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:assembleDebug :app:lintDebug
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:testDebugUnitTest
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/adb.ps1 devices
```

Para comandos ADB direcionados quando houver mais de um aparelho/emulador,
selecione explicitamente o serial:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/adb.ps1 -s SERIAL shell getprop ro.build.version.sdk
```

Durante a preparação física, use ADB somente para consultar metadados:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/adb.ps1 devices
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/adb.ps1 -s SERIAL shell getprop ro.build.version.sdk
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/adb.ps1 -s SERIAL shell getprop ro.product.model
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/adb.ps1 -s SERIAL shell pm list packages c6
```

Confirme o aparelho e o pacote-alvo com o usuário antes de qualquer instalação
ou concessão de acesso; instalação também requer autorização explícita. Com
vários aparelhos/emuladores, use `-s SERIAL` em
cada comando direcionado; não registre seriais nem saídas que possam conter
dados pessoais. Não consulte conteúdo de notificações via ADB, `logcat` ou
comandos de dump.

## Comandos no WSL

Com a interoperabilidade WSL/Windows habilitada, execute na raiz do projeto.
A mesma pré-condição de autorização explícita vale para qualquer comando ADB:

```bash
powershell.exe -NoProfile -ExecutionPolicy Bypass \
  -File "$(wslpath -w "$PWD/tools/gradle.ps1")" \
  :app:assembleDebug :app:lintDebug
powershell.exe -NoProfile -ExecutionPolicy Bypass \
  -File "$(wslpath -w "$PWD/tools/gradle.ps1")" \
  :app:testDebugUnitTest
powershell.exe -NoProfile -ExecutionPolicy Bypass \
  -File "$(wslpath -w "$PWD/tools/adb.ps1")" devices
powershell.exe -NoProfile -ExecutionPolicy Bypass \
  -File "$(wslpath -w "$PWD/tools/adb.ps1")" -s SERIAL shell getprop ro.build.version.sdk
powershell.exe -NoProfile -ExecutionPolicy Bypass \
  -File "$(wslpath -w "$PWD/tools/adb.ps1")" -s SERIAL shell getprop ro.product.model
powershell.exe -NoProfile -ExecutionPolicy Bypass \
  -File "$(wslpath -w "$PWD/tools/adb.ps1")" -s SERIAL shell pm list packages c6
```

O APK de depuração, quando o build concluir, fica em
`app/build/outputs/apk/debug/app-debug.apk`.

## Configuração local

O nome de pacote deste app é `br.com.c6tag.diagnostico`. O pacote de origem do
C6 tem precedência `-Pc6PackageName=...`, depois `c6.packageName` em
`local.properties`, e por fim vazio. Uma opção `-P` explicitamente vazia também
prevalece e mantém a captura desativada. Valor vazio significa **off**; nunca
significa observar todos os pacotes.

Só depois de identificar o pacote instalado por consulta de metadados e obter
confirmação do usuário, adicione `c6.packageName=<PACOTE_CONFIRMADO>` ao
`local.properties` local e ignorado. Preserve as demais linhas existentes
(inclusive `sdk.dir`); não substitua o arquivo nem adivinhe o pacote. Recompile
com o valor confirmado explícito, que prevalece sobre a configuração local:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:assembleDebug "-Pc6PackageName=<PACOTE_CONFIRMADO>" --console=plain
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/adb.ps1 -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

Conceder **Acesso a notificações** é uma ação manual nas configurações do
Android, nunca automática pelo app. Essa permissão especial pode dar ao app
acesso amplo às notificações do aparelho; conceda-a somente após revisar o
risco e confirmar o pacote. Para desativar a captura, revogue o acesso especial
nas configurações do Android. O estado conectado do listener e o snapshot de
notificações ativas são observações separadas; nenhum deles garante captura
completa ou confirmação de transação.

Na tela, **Limpar registros** pede confirmação e apaga apenas os registros
internos; arquivos exportados não são removidos. **Exportar diagnóstico** é
manual, mostra aviso sensível e permite cancelar antes de escolher o destino.
O arquivo pode conter dados pessoais: revise e oculte-os antes de compartilhar;
um destino em nuvem pode sincronizar o arquivo. A exportação não é anonimizada.
Quando chega um evento novo, a lista volta à primeira página; use **Carregar
anteriores** para navegar pelos registros mais antigos.

A tela não calcula saldo nem emite alertas operacionais. Classificações são
candidatas experimentais e a captura pode estar incompleta. Não faça
transações para testar o app; compare manualmente apenas notificações que
surjam naturalmente e estejam autorizadas para revisão.

## Exportação e privacidade

A exportação JSON é manual: o app mostra um aviso sensível antes de abrir o
seletor de destino do Android. O arquivo pode conter nomes, CPF parcial,
locais e conteúdo de notificações; revise e oculte dados pessoais antes de
compartilhar. O app não anonimiza o conteúdo. Um destino em nuvem pode
sincronizar o arquivo mesmo que este app não tenha acesso à internet. A
exportação não é compartilhada automaticamente, e **Limpar registros** não
apaga arquivos exportados.

O app mantém `allowBackup=false` e também declara exclusões explícitas para
backup legado, backup em nuvem e transferência padrão entre dispositivos.
Essas regras não prometem proteção contra root, capturas de tela, exportação
manual, nem políticas de fabricantes fora do mecanismo padrão do Android.

Sem configuração local ou `-P`, o pacote-alvo fica vazio e a captura é desativada.
Nesta sessão, o usuário confirmou `com.c6bank.app`, autorizou a instalação no
Galaxy S23 (SM-S911B, Android 17/API 37) e realizou o acesso manual às notificações.
Relatou 8 avisos de tags, 2 propagandas sem valor e reconhecimento de um crédito
Pix de R$ 1,00, com atualização da lista. Nenhum acesso foi concedido por ADB.

A revisão final aprovou o código para diagnóstico assistido. Ainda faltam
observações de débito novo natural, tela bloqueada, reconexão, reinicialização,
repetições e exportação no aparelho. Esses resultados iniciais não provam captura
completa, correção financeira ou confiabilidade futura; saldo e alertas não estão
implementados.

`local.properties` é local e ignorado pelo controle de versão. Arquivos de
exportação, dumps e dados pessoais também devem permanecer fora do repositório.
