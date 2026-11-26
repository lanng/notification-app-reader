# Tag Alert (Android)

Aplicativo Android pessoal desenvolvido em **Kotlin + Jetpack Compose** para acompanhar a estimativa de saldo de uma conta dedicada a tags de pedágio (**C6 Tag**) e acionar alertas escalonados — incluindo **alarme crítico em tela cheia** — tanto no aparelho principal (**Host**) quanto em aparelhos autorizados de apoio (**Convidado**).

> **Aviso:** este é um projeto independente e pessoal. Não é um produto oficial do C6 Bank, não solicita senha bancária, não acessa a conta do banco nem realiza recargas.

---

## Problema resolvido

Em operações com veículos/frota usando tags de pedágio, ficar sem saldo na conta dedicada pode causar bloqueios imediatos nas praças de pedágio. O **Tag Alert** acompanha o saldo estimado a partir das notificações recebidas no aparelho principal e alerta de forma progressiva antes que o saldo chegue a um nível crítico.

---

## Funcionalidades

### Aparelho Host (principal)
- **Acompanhamento conservador de saldo:** funciona a partir de um **saldo de referência** informado manualmente pelo usuário e atualiza a estimativa com base em notificações elegíveis (débitos de tag e créditos Pix) capturadas via `NotificationListenerService`.
- **Histórico financeiro e pendências:** lista paginada com filtros (*Todas*, *Entradas*, *Saídas*, *Ajustes*) e fila explícita de revisão para observações ambíguas ou concorrentes.
- **Funcionamento offline-first:** visão geral, histórico, ajustes e alertas locais operam localmente em SQLite mesmo sem rede ou login.
- **Gestão de convidados (suportes):** autenticação do proprietário via Credential Manager + Firebase Auth (Google), geração de convites temporários de copiar e colar, aprovação/rejeição explícita de solicitações e revogação de acessos.

### Aparelho Convidado (suporte · somente leitura)
- **Pareamento sem login Google:** entra por convite emitido pelo Host, aguardando aprovação explícita.
- **Visão somente leitura:** exibe o saldo estimado sincronizado, o histórico financeiro sanitizado (paginado e filtrável) e as regras de limite/repetição herdadas do Host (não editáveis pelo convidado).
- **Sem captura bancária:** não solicita acesso às notificações do sistema.

### Sistema de alertas escalonados
- **Normal** (`saldo >= limite`): sem alertas ativos.
- **Atenção** (`metade <= saldo < limite`):
  - **1º aviso:** enviado apenas no Host.
  - **A partir do 2º aviso** (ou após o intervalo configurado): notifica Host e Convidados, repetindo conforme o intervalo definido no Host (`15`, `30` ou `60 min`).
- **Crítico** (`saldo < metade do limite`):
  - Dispara **imediatamente** no Host e nos Convidados (via **Firebase Cloud Messaging** com fallback via **WorkManager**), ignorando o tempo restante do intervalo de atenção e interrompendo pausas de atenção.
  - Abre **tela cheia estilo alarme** (com a tela bloqueada via `USE_FULL_SCREEN_INTENT` e, opcionalmente, sobre outros apps via `SYSTEM_ALERT_WINDOW`), acompanhada de **som contínuo de alarme por 30 segundos**, vibração e botão **Parar alarme**.
- **Pausa independente por aparelho:** ao tocar em **Entendi**, a pausa vale apenas para aquele aparelho (o Host pausar não silencia o Convidado e vice-versa).

---

## Arquitetura e stack

- **Linguagem e UI:** Kotlin 2.2, Jetpack Compose, Material 3 (temas Sistema / Claro / Escuro)
- **Persistência local:** SQLite (`SQLiteOpenHelper`) com migrações aditivas e política de backup desativada (`allowBackup=false`)
- **Segurança local:** sessões remotas cifradas com **Android Keystore (AES-GCM)** em arquivos privados fora do backup
- **Privacidade:** textos brutos de notificações bancárias permanecem exclusivamente no aparelho Host; apenas dados sanitizados (valores, tipo de movimento, timestamps e estado do alerta) são sincronizados com a API
- **Rede e sincronização:** cliente HTTPS estrito (`HttpsURLConnection`) com validação de origem canônica e rotas permitidas, **Firebase Auth**, **Firebase Cloud Messaging (FCM)** e **WorkManager**
- **Testes:** 440+ testes unitários JVM (regras de saldo, política de alertas Host e Convidado, codecs/criptografia, clientes HTTP, fluxo de pareamento) + suíte de testes instrumentados

---

## Como compilar e executar os testes

### Pré-requisitos
- **JDK 21** e **Android SDK (API 36)**
- Arquivo local `app/google-services.json` do seu projeto Firebase (ignorado pelo Git)

### Rodar testes unitários, compilação e Lint
```powershell
.\gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```
*(No Windows/WSL também está disponível o wrapper `tools/gradle.ps1`.)*

### Compilar APK debug configurado para um ambiente próprio
Por padrão, `OWNER_API_ORIGIN` e `C6_PACKAGE_NAME` ficam vazios no repositório. Para gerar um APK apontando para sua API HTTPS e pacote-alvo:

```powershell
.\gradlew :app:assembleDebug `
  -PownerApiOrigin=https://sua-api.exemplo.com `
  -Pc6PackageName=com.c6bank.app
```

O APK gerado fica em `app/build/outputs/apk/debug/app-debug.apk`.

---

## Repositório complementar

- **Backend API (Laravel + PostgreSQL + Sanctum + FCM):** `notification-app-api`
