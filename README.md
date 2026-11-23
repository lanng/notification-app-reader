# Monitor de tags

Aplicativo Android pessoal para acompanhar uma **estimativa** do saldo de uma
conta exclusiva para C6 Tag, usando notificações reconhecidas do C6.
Não consulta o saldo no banco, não pede senha, não realiza recargas e não envia
avisos bancários nem dados financeiros a um servidor. O monitor local continua
independente de login e de rede. Não é um produto oficial do banco.

**Documentação de trabalho:** `docs/` é mantida apenas localmente e ignorada pelo
Git. As referências a essa pasta abaixo são locais/históricas; esses arquivos
não acompanham novos clones nem são necessários para compilar ou executar testes.

**Estado de desenvolvimento:** Tarefas 1–8 implementadas e revisadas, incluindo
histórico paginado, pendências e ajustes detalhados. A integração automatizada
da Tarefa 9 passou em 112 testes JVM e 144 instrumentados, com lint sem erros.
TalkBack confirmado pelo usuário no percurso orientado da Visão geral.
O S23 foi atualizado para 1.1/código 2 com autorização, e a sessão assistida foi
encerrada com evidência parcial: registros anteriores, estimativa, débitos naturais,
primeiro alerta, estados de pausa/retomada e restauração do limite confirmados
por relato. Crédito natural, entrega temporal dos lembretes e ensaios de
interrupção continuam não observados; não há validação física integral.
Qualquer nova instalação/alteração física exige autorização. Evidências atuais em
[`docs/testing/monitor-local-results.md`](docs/testing/monitor-local-results.md).

**Ajuste visual posterior:** ícones inferiores trocados por vetores de 28 dp,
com rótulos e áreas de toque preservados. APK local revisado e verificado:
112 JVM / 146 instrumentados, lint 0 erros / 8 avisos anteriores.
Essa revisão visual ainda não foi instalada no S23 pelo assistente.
Artefato e evidências em
[`docs/testing/bottom-navigation-icons-results.md`](docs/testing/bottom-navigation-icons-results.md).

**Ícone do app:** a arte fornecida pelo usuário foi adaptada para o launcher
Android, com recursos adaptativos e fallbacks por densidade, sem substituir o
ícone monocromático das notificações. APK atualizado para instalação manual:
112 JVM / 150 instrumentados aprovados; lint 0 erros / 14 avisos documentados.
Fonte, reprodução, prévias e hash em
[`docs/testing/launcher-artwork-results.md`](docs/testing/launcher-artwork-results.md).

**Fundação remota (somente debug):** há fixtures e uma entrada manual em
Ajustes para uma notificação crítica sintética; isso não implementa comunicação
remota. A opção de tela cheia começa desligada; ativá-la exige consentimento
explícito e acesso especial concedido separadamente, com fallback de notificação
comum. O release exclui a sonda. Áudio, visibilidade e duração real não foram
comprovados em aparelho, e não há garantia de áudio por 30 segundos. A fundação
de identidade Google/Firebase está incluída, mas não está ligada à interface e
nenhum fluxo real de login Google foi executado ou é acionado por este app.
Também não há API de host. A permissão de internet atende à autenticação futura;
o monitor local não depende dela. Uma identidade Firebase, quando conectada
futuramente, será apenas candidata e não autoriza por si só um host remoto ou
compartilhamento entre aparelhos.
Para preparar a configuração Firebase local necessária ao build, consulte
[Configuração local do Firebase](#configuração-local-do-firebase).
Roteiro físico e evidências
locais: [`docs/testing/critical-alert-probe-checklist.md`](docs/testing/critical-alert-probe-checklist.md)
e [`docs/testing/remote-mobile-foundation-results.md`](docs/testing/remote-mobile-foundation-results.md).

## Como funciona

- Consulte o saldo atual no C6 e informe um **saldo de referência**. Ele já
  inclui o passado: avisos anteriores não são reaplicados ao saldo inicial.
- Créditos Pix e débitos de tag reconhecidos e elegíveis atualizam a estimativa
  uma única vez. Propagandas e resumos não são movimentações.
- Uma **correção** substitui a estimativa e preserva o histórico. Não é receita
  ou recarga. Valores negativos são permitidos, sem arredondamento silencioso.
- Avisos que chegam durante a conferência ficam identificados como pendentes,
  sem serem incluídos implicitamente no saldo informado.
- Dúvidas de identidade, valor ou continuidade sinalizam **Saldo a conferir**.
  Reconectar não prova que nenhuma notificação foi perdida.
- Abaixo do limite, o app solicita o primeiro aviso local após persistir a
  alteração. Lembretes complementares usam WorkManager, com repetição prevista
  de 15 minutos por padrão; o Android pode atrasá-los.
- **Ciência** pausa os lembretes por 30 minutos por padrão, sem aumentar saldo
  nem resolver o ciclo. Uma recuperação confiável ao limite ou acima o encerra.
- Abrir ou descartar uma notificação não significa ciência. Som depende do canal,
  volume, Não Perturbe e permissões; não é garantido pelo app.

Visão geral, Histórico e Ajustes seguem os temas Sistema/Claro/Escuro.
O diagnóstico técnico permanece em Ajustes, separado do histórico financeiro.
Comunicação com outros aparelhos não está implementada nesta versão.

## Ferramentas

- Windows com JDK 21 e Android SDK (API 36 e Build Tools 36.0.0).
- O script `tools/gradle.ps1` localiza o JDK 21 e o SDK Windows; caminhos
  configurados em `JAVA_HOME`, `ANDROID_SDK_ROOT` ou `ANDROID_HOME` são usados
  quando válidos. O script não instala ferramentas.
- Gradle Wrapper 9.1.0. As versões de plugins e bibliotecas estão fixadas em
  `gradle/libs.versions.toml`.
- Não é necessário instalar Gradle globalmente.

## Configuração local do Firebase

O plugin Google Services precisa do arquivo local `app/google-services.json`
para gerar os recursos Firebase usados pelo build e pelos testes do app. O clone
não inclui esse arquivo porque ele é intencionalmente ignorado pelo Git. Antes
dos comandos Gradle abaixo, prepare uma configuração do projeto Firebase
`tag-alert-ba5d8` para o registro Android com `applicationId`
`br.com.tagalert.app`.

Com acesso autorizado, abra o Firebase Console e vá a **Project settings >
General > Your apps**. Selecione o app Android registrado com esse
`applicationId`, baixe o `google-services.json` e coloque-o em
`app/google-services.json`. Se já houver um arquivo local, não o sobrescreva;
confirme com o responsável pelo projeto. Sem acesso, peça a configuração ao
responsável. Não crie um JSON fictício nem publique ou compartilhe o conteúdo
ou as chaves do arquivo. O comando somente de leitura
`git check-ignore -v app/google-services.json` confirma que ele fica fora do
versionamento.

O plugin gera `default_web_client_id` a partir da configuração; o
`GoogleCredentialRequestor` usa esse recurso como web client ID na solicitação
Google. Esse ID não é o OAuth client Android; não cole tokens manualmente. O
`google-services.json` é configuração Firebase de cliente, não credencial
privada de service account/Admin SDK. Nunca inclua JSON de service account nem
chave privada no app Android.

Isso é somente um pré-requisito do build: o monitor local instalado continua
utilizável sem login Google e offline. A configuração não conecta a fundação de
identidade Firebase à interface nem comprova login real ou operação em
produção; nenhum fluxo real de login foi executado.

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

A configuração local do Firebase necessária antes dos comandos Gradle está em
[Configuração local do Firebase](#configuração-local-do-firebase).

O `applicationId` deste app para instalação é `br.com.tagalert.app`; o namespace
Android interno continua `br.com.c6tag.diagnostico`. O pacote de origem do C6 tem
precedência `-Pc6PackageName=...`, depois `c6.packageName` em
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

Nas versões aplicáveis, **avisos deste app** também exigem `POST_NOTIFICATIONS`,
solicitada somente após explicação e toque explícito. Essa permissão é diferente
do acesso especial à captura. Recusar avisos não bloqueia consulta a saldo ou
histórico; a interface indica a indisponibilidade. O som é escolhido no canal
Android, sem o app contornar preferências ou restaurar escolhas silenciosamente.

No diagnóstico, a limpeza pede confirmação e apaga apenas observações técnicas;
saldo, histórico financeiro e identidades são preservados. Arquivos exportados
não são removidos. **Exportar diagnóstico** é
manual, mostra aviso sensível e permite cancelar antes de escolher o destino.
O arquivo pode conter dados pessoais: revise e oculte-os antes de compartilhar;
um destino em nuvem pode sincronizar o arquivo. A exportação não é anonimizada.
Quando chega um evento novo, a lista volta à primeira página; use **Carregar
anteriores** para navegar pelos registros mais antigos.

A tela técnica de diagnóstico não é um extrato nem calcula o saldo: o
acompanhamento financeiro é separado e conservador. A captura pode estar
incompleta. Não faça transações para testar o app; compare manualmente apenas
notificações que surjam naturalmente e estejam autorizadas para revisão.

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

A revisão anterior aprovou o coletor para diagnóstico assistido. O monitor local
foi implementado posteriormente e testado com dados sintéticos no emulador;
isso não atualizou nem validou a versão financeira no S23. Observações de débito
novo natural, tela bloqueada, reconexão, reinicialização, repetições e exportação
físicas continuam dependentes de consentimento. O resultado inicial do coletor
não prova captura completa, correção financeira ou confiabilidade futura.

`local.properties` é local e ignorado pelo controle de versão. Arquivos de
exportação, dumps e dados pessoais também devem permanecer fora do repositório.

## Verificação e atualização assistida

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

Instrumentados devem usar somente um emulador sintético explicitamente
selecionado com `ANDROID_SERIAL`. Não executar testes instrumentados contra a
instalação do usuário. XMLs e limites da evidência estão no relatório do monitor.

Roteiro físico ainda não executado:
[`docs/testing/monitor-local-s23-checklist.md`](docs/testing/monitor-local-s23-checklist.md).
A nova identidade instalada `br.com.tagalert.app` é diferente do pacote de versões
anteriores (`br.com.c6tag.diagnostico`). O Android não a tratará como atualização:
ela será instalada separadamente e não herdará os dados nem as permissões ou
concessões da versão anterior. A versão antiga e seus dados só mudam por uma ação
separada do usuário. Qualquer instalação física continua exigindo autorização
explícita; confira novamente e conceda manualmente as permissões necessárias para
a nova instalação.

## Desenvolvimento e Git

O histórico local usa Conventional Commits em inglês. Identificadores e
comentários de código são em inglês; textos da interface são pt-BR.
Cada etapa requer testes e revisão antes de commit. Não versionar configuração
local, caches, APKs, keystores, bancos, logs, exports ou dados pessoais.
O remoto GitHub será conectado pelo usuário; não há push automático.
