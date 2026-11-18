# PTZ Studio Android

Aplicativo Android para comandar câmera PTZ (VISCA por UDP) com o controle **GameSir Nova 2 Lite** ou com o joystick da tela.
Feito para transmissões ao vivo: movimento suave, proporcional à inclinação do analógico, e zoom proporcional à pressão dos gatilhos.

Testado para compilar para Android 8 ou mais novo (alvo: Samsung Galaxy S23 Ultra).

## Como instalar no celular

1. No celular, abra a página **Releases** deste repositório: <https://github.com/eleonardop/ptz-android/releases>.
2. Na versão mais nova, toque no arquivo **PTZ-Studio-Android-0.1.N.apk** para baixar.
3. Abra o arquivo baixado. Na primeira vez o Android pede para **permitir instalar apps desta fonte** (Chrome ou Meus Arquivos): permita e volte.
4. Toque em **Instalar**.

As próximas versões instalam por cima, sem perder as câmeras e o mapeamento.

## Primeiro uso

1. Ligue o celular no **mesmo Wi-Fi** da câmera.
2. Toque no ícone de lista (Câmeras) → **Adicionar câmera pelo IP** (ou **Adicionar** numa câmera encontrada na rede).
3. Preencha o IP (ex.: `192.168.4.163`), o protocolo e a porta:
   - **UDP (VISCA puro)** – porta 1259 (a mesma do PTZ Studio do Windows);
   - **VISCA over IP (Sony)** – porta 52381.
   Use o mesmo protocolo e porta que já funcionam no PTZ Studio do Windows.
4. (Opcional) Endereço RTSP do vídeo, por exemplo `rtsp://192.168.4.163:554/2`. Prefira o fluxo secundário (mais leve).
5. Toque em **Usar esta câmera**. No topo da tela de controle aparece **Câmera conectada** quando ela responde.

### Controle GameSir

Pareie o controle no **Bluetooth do celular** (ou ligue por cabo USB‑C) no modo para Android. Quando o celular reconhece,
aparece **Controle** no topo da tela. O controle só funciona com o app aberto na tela (regra do Android).

## Funções prontas do controle

| Botão | Função |
|---|---|
| Analógico esquerdo | Mover câmera (Pan/Tilt), velocidade pela inclinação |
| Analógico direito | Movimento fino (lento) |
| RT / LT | Zoom + / Zoom − pela pressão do gatilho |
| Direcional ↑ ↓ ← → | Tilt cima, Tilt baixo, Pan esquerda, Pan direita |
| A / B / X / Y | Preset 1 / 2 / 3 / 4 |
| RB / LB | Velocidade + / − |
| R4 / L4 | Brilho + / − |
| Clique do analógico esquerdo (L3) | Autofoco |
| Clique do analógico direito (R3) | Parar câmera |
| Home, View, Menu, Screenshot, M | Nenhuma |

Cada botão pode ser mudado em **Ajustes → Mapear botões do controle**. **Restaurar padrão** volta para esta lista.

## Telas

- **Controle**: vídeo ao vivo (RTSP), joystick da tela como no Windows (arrastar o centro = movimento gradual; 8 botões de direção em volta, com diagonais = segurar para mover),
  zoom (segurar), velocidade do Pan/Tilt e do zoom de 1% a 100%, zoom atual, presets 1 a 8 (tocar = ir; tocar e segurar = gravar
  posição / mudar nome), Autofoco, Brilho, Home.
- **Câmeras**: câmeras salvas com status (online/sem resposta), busca ONVIF na rede e cadastro pelo IP.
- **Ajustes**: velocidade, aceleração gradual, zona morta, movimento fino, velocidade do zoom, trava de diagonal, inversões, vídeo e
  **Pan/Tilt mais lento com zoom alto**: o app lê a posição do zoom na câmera e reduz a velocidade do Pan/Tilt conforme o zoom
  aumenta (padrão: 80% mais lento com o zoom no máximo).
- **Mapeamento**: desenho do controle, função de cada botão e **Detectar botão** (aperte no controle para encontrar).

## Compilação

O GitHub Actions compila a cada envio para `main`: roda os testes automáticos, gera o APK e publica em **Releases**.
Detalhes dos testes em [TESTES.md](TESTES.md).

A chave de assinatura `app/debug.keystore` é uma chave de teste guardada no repositório para que cada versão instale por cima
da anterior. Não serve para publicar na Play Store.
