# Relatório de testes – PTZ Studio Android 0.1

## Testes automáticos (rodam em toda compilação no GitHub Actions)

Ficam em `app/src/test`. Não usam câmera nem controle de verdade.

| Arquivo | O que confere |
|---|---|
| `ViscaTest` | Bytes de cada comando VISCA (Pan/Tilt, parada, zoom, foco, autofoco, presets, Home, brilho, consulta) e o cabeçalho do VISCA over IP |
| `MotionTest` | Zona morta sem salto, curva com mais precisão perto do centro, trava de diagonal, rampa de aceleração, parada imediata ao soltar, inversão de sentido sem tranco |
| `ControllerTest` | Movimento gradual (a velocidade sobe aos poucos), velocidade proporcional à inclinação, limite de velocidade em %, parada no mesmo ciclo ao soltar e repetida por ~5 s, reenvio a cada 400 ms enquanto segura, sem diagonal ao mover na horizontal, cima = cima (sem inversão), zona morta, movimento fino, **zoom proporcional à pressão do gatilho e parada imediata ao soltar**, direcional, joystick da tela junto com o controle, Parar câmera, foco manual |
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
- [ ] Vídeo RTSP abre e não atrasa os comandos.
- [ ] Busca ONVIF encontra a câmera (nem toda câmera responde).
- [ ] Ao sair do app ou desligar o controle, a câmera para.
