# Relatório de testes – PTZ Studio Android 0.1

## Testes automáticos (rodam em toda compilação no GitHub Actions)

Ficam em `app/src/test`. Não usam câmera nem controle de verdade.

| Arquivo | O que confere |
|---|---|
| `ViscaTest` | Bytes de cada comando VISCA (Pan/Tilt, parada, zoom, foco, autofoco, presets, Home, brilho, consultas) e o cabeçalho do VISCA over IP; leitura da posição do zoom; fator de redução pelo zoom |
| `MotionTest` | Zona morta sem salto, curva com mais precisão perto do centro, trava de diagonal, rampa de aceleração, parada imediata ao soltar, inversão de sentido sem tranco |
| `ControllerTest` | Movimento gradual (a velocidade sobe aos poucos), velocidade proporcional à inclinação, limite de velocidade em %, parada no mesmo ciclo ao soltar e repetida por ~5 s, reenvio a cada 400 ms enquanto segura, sem diagonal ao mover na horizontal, cima = cima (sem inversão), zona morta, movimento fino, **zoom proporcional à pressão do gatilho e parada imediata ao soltar**, velocidade do zoom em %, **Pan/Tilt mais lento com zoom alto** (analógico, joystick da tela e direcional; sem redução se desligada ou sem posição), direcional, joystick da tela junto com o controle, Parar câmera, foco manual |
| `ControllerTest` (segurança) | Parada de segurança só volta a mover depois de soltar tudo; preset/Home não são cancelados por um analógico ainda inclinado; reenvio da parada quando a câmera volta a responder (sem atrapalhar quem está usando o controle) |
| `MappingTest` | Lista pronta cobre os 23 botões com as funções combinadas (direcional = Pan/Tilt, A/B/X/Y = presets 1–4, Home = nenhuma), opções por tipo de botão, preset dispara uma vez por toque, gatilhos viram zoom proporcional, gatilho como botão, analógico direito = fino |
| `NetTest` | UDP de verdade dentro do próprio computador (127.0.0.1): envio puro, VISCA over IP com sequência, sondagem de câmera que responde e que não responde; leitura da resposta da busca ONVIF |
| `SettingsCodecTest` | Gravar e ler as configurações; arquivo vazio, estragado ou incompleto volta ao padrão |

## O que depende da câmera e do controle reais (não foi testado)

Estes itens só podem ser confirmados no celular, com a LJ510-30X-N e o GameSir:

- [ ] A câmera responde no protocolo e porta escolhidos (status **Câmera conectada**).
- [ ] Pan/Tilt pelo analógico esquerdo: devagar com pouca inclinação, rápido com inclinação total, para ao soltar.
- [ ] Horizontal sem diagonal; cima move para cima.
- [ ] Zoom pelos gatilhos: lento com pouca pressão, rápido com pressão total, para ao soltar.
- [ ] Direcional move Pan/Tilt; A/B/X/Y chamam os presets 1–4.
- [ ] Presets gravados pelo app (tocar e segurar) são chamados corretamente.
- [ ] Autofoco e Brilho +/− têm efeito na LJ510-30X-N (o brilho usa a compensação de exposição VISCA).
- [ ] Quais botões o Android recebe do GameSir: Home, Screenshot, M, L4 e R4 podem não chegar ao app (usar **Detectar botão**).
- [ ] A LJ510-30X-N responde à consulta da posição do zoom (Ajustes mostra "Zoom agora: X%"). Se o zoom todo aproximado não mostrar 100%, usar **Calibrar**.
- [ ] Com o zoom alto, o Pan/Tilt fica lento o suficiente (ajustar "Redução com o zoom no máximo").
- [ ] Os 8 botões de direção do joystick da tela (inclusive diagonais) não puxam o joystick, e o arraste do centro é confortável.
- [ ] Vídeo RTSP abre e não atrasa os comandos.
- [ ] Busca ONVIF encontra a câmera (nem toda câmera responde).
- [ ] Ao sair do app ou desligar o controle, a câmera para.
- [ ] Abrir a cortina de notificações segurando o direcional/analógico: a câmera para (e só volta a mover depois de soltar).
- [ ] Gatilho com toque leve faz zoom lento (o "botão" R2/L2 não passa por cima da pressão).
- [ ] Wi-Fi da câmera sem internet e dados móveis ligados: a câmera continua respondendo.
- [ ] Desligar o Wi-Fi com a câmera andando: aparece "Câmera sem resposta: movimento parado"; ao voltar, a câmera fica parada.
- [ ] Segurar um analógico inclinado parado por mais de 5 s: a câmera NÃO deve parar sozinha (se parar, desligar "Segurança: parar se o controle parar de mandar sinal" e avisar).
- [ ] Detectar botão: a janela de detecção recebe os botões do controle.
- [ ] Vídeo volta sozinho depois de uma queda e para de baixar com a tela bloqueada.
