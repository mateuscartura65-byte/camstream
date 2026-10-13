# CameraParaPC

App Android que transmite a camera do celular para o PC, de qualquer lugar
(pela internet), sem cadastro e sem configurar roteador.

## Como usar

1. Baixe o APK em Actions > Build APK > app-debug.
2. Instale no celular e abra. O app ja liga a camera e entra no ar sozinho.
3. Abra o `viewer.html` no PC e digite o MESMO codigo que aparece no celular.

Pronto. O botao vermelho existe mas nao faz nada (por enquanto).

## Como o APK e gerado

Cada push na branch `main` dispara o GitHub Actions, que compila o APK.
Nao precisa instalar Android Studio.

## Rodar local (opcional)

    gradle assembleDebug

O APK fica em `app/build/outputs/apk/debug/app-debug.apk`.