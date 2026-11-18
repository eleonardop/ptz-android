package com.ptzstudio.android.core

/** Tipo de entrada física: analógico (2 eixos), gatilho (pressão 0..1) ou botão (aperta/solta). */
enum class InputKind { STICK, TRIGGER, BUTTON }

/** Grupos mostrados na tela de mapeamento. */
enum class PadGroup(val title: String) {
    SHOULDERS("Gatilhos e ombros"),
    BACK("Botões traseiros"),
    STICKS("Analógicos"),
    DPAD("Direcional"),
    ACTION("Botões de ação"),
    CENTER("Botões centrais"),
}

/** Todos os botões do GameSir Nova 2 Lite, cada um configurado individualmente. */
enum class PadButton(val badge: String, val label: String, val group: PadGroup, val kind: InputKind) {
    LT("LT", "Gatilho esquerdo", PadGroup.SHOULDERS, InputKind.TRIGGER),
    RT("RT", "Gatilho direito", PadGroup.SHOULDERS, InputKind.TRIGGER),
    LB("LB", "Ombro esquerdo", PadGroup.SHOULDERS, InputKind.BUTTON),
    RB("RB", "Ombro direito", PadGroup.SHOULDERS, InputKind.BUTTON),
    L4("L4", "Traseiro esquerdo", PadGroup.BACK, InputKind.BUTTON),
    R4("R4", "Traseiro direito", PadGroup.BACK, InputKind.BUTTON),
    LS("LS", "Analógico esquerdo", PadGroup.STICKS, InputKind.STICK),
    L3("L3", "Apertar analógico esq.", PadGroup.STICKS, InputKind.BUTTON),
    RS("RS", "Analógico direito", PadGroup.STICKS, InputKind.STICK),
    R3("R3", "Apertar analógico dir.", PadGroup.STICKS, InputKind.BUTTON),
    DPAD_UP("↑", "Direcional cima", PadGroup.DPAD, InputKind.BUTTON),
    DPAD_RIGHT("→", "Direcional direita", PadGroup.DPAD, InputKind.BUTTON),
    DPAD_DOWN("↓", "Direcional baixo", PadGroup.DPAD, InputKind.BUTTON),
    DPAD_LEFT("←", "Direcional esquerda", PadGroup.DPAD, InputKind.BUTTON),
    A("A", "Botão A", PadGroup.ACTION, InputKind.BUTTON),
    B("B", "Botão B", PadGroup.ACTION, InputKind.BUTTON),
    X("X", "Botão X", PadGroup.ACTION, InputKind.BUTTON),
    Y("Y", "Botão Y", PadGroup.ACTION, InputKind.BUTTON),
    VIEW("View", "Botão View", PadGroup.CENTER, InputKind.BUTTON),
    MENU("Menu", "Botão Menu", PadGroup.CENTER, InputKind.BUTTON),
    HOME("Home", "Botão Home", PadGroup.CENTER, InputKind.BUTTON),
    SCREENSHOT("Captura", "Botão Screenshot", PadGroup.CENTER, InputKind.BUTTON),
    M("M", "Botão M", PadGroup.CENTER, InputKind.BUTTON);

    companion object {
        fun fromName(name: String?): PadButton? = entries.firstOrNull { it.name == name }

        /** Botões que o Android pode não reconhecer sozinho: o usuário ensina o código na detecção. */
        val learnable = listOf(L4, R4, SCREENSHOT, M, HOME, VIEW, MENU)
    }
}

/** O que cada botão pode fazer. [kinds] = tipos de entrada que aceitam a função. */
enum class PadAction(val label: String, val kinds: Set<InputKind>) {
    NONE("Nenhuma", setOf(InputKind.STICK, InputKind.TRIGGER, InputKind.BUTTON)),

    // Analógicos (movimento gradual, proporcional à inclinação)
    STICK_MOVE("Mover câmera (Pan/Tilt)", setOf(InputKind.STICK)),
    STICK_FINE("Movimento fino (lento)", setOf(InputKind.STICK)),
    STICK_PAN_ONLY("Só Pan (horizontal)", setOf(InputKind.STICK)),
    STICK_TILT_ONLY("Só Tilt (vertical)", setOf(InputKind.STICK)),
    STICK_ZOOM("Zoom (cima/baixo)", setOf(InputKind.STICK)),

    // Gatilhos (zoom gradual, proporcional à pressão)
    ZOOM_IN_PRESSURE("Zoom + (pela pressão)", setOf(InputKind.TRIGGER)),
    ZOOM_OUT_PRESSURE("Zoom − (pela pressão)", setOf(InputKind.TRIGGER)),

    // Botões (gatilhos também podem usar como botão)
    PAN_LEFT("Pan esquerda", setOf(InputKind.BUTTON, InputKind.TRIGGER)),
    PAN_RIGHT("Pan direita", setOf(InputKind.BUTTON, InputKind.TRIGGER)),
    TILT_UP("Tilt cima", setOf(InputKind.BUTTON, InputKind.TRIGGER)),
    TILT_DOWN("Tilt baixo", setOf(InputKind.BUTTON, InputKind.TRIGGER)),
    AUTOFOCUS("Autofoco", setOf(InputKind.BUTTON, InputKind.TRIGGER)),
    FOCUS_FAR("Foco +", setOf(InputKind.BUTTON, InputKind.TRIGGER)),
    FOCUS_NEAR("Foco −", setOf(InputKind.BUTTON, InputKind.TRIGGER)),
    ZOOM_IN("Zoom +", setOf(InputKind.BUTTON, InputKind.TRIGGER)),
    ZOOM_OUT("Zoom −", setOf(InputKind.BUTTON, InputKind.TRIGGER)),
    SPEED_UP("Velocidade +", setOf(InputKind.BUTTON, InputKind.TRIGGER)),
    SPEED_DOWN("Velocidade −", setOf(InputKind.BUTTON, InputKind.TRIGGER)),
    BRIGHT_UP("Brilho +", setOf(InputKind.BUTTON, InputKind.TRIGGER)),
    BRIGHT_DOWN("Brilho −", setOf(InputKind.BUTTON, InputKind.TRIGGER)),
    HOME("Home (posição inicial)", setOf(InputKind.BUTTON, InputKind.TRIGGER)),
    STOP("Parar câmera", setOf(InputKind.BUTTON, InputKind.TRIGGER)),
    PRESET_1("Preset 1", setOf(InputKind.BUTTON, InputKind.TRIGGER)),
    PRESET_2("Preset 2", setOf(InputKind.BUTTON, InputKind.TRIGGER)),
    PRESET_3("Preset 3", setOf(InputKind.BUTTON, InputKind.TRIGGER)),
    PRESET_4("Preset 4", setOf(InputKind.BUTTON, InputKind.TRIGGER)),
    PRESET_5("Preset 5", setOf(InputKind.BUTTON, InputKind.TRIGGER)),
    PRESET_6("Preset 6", setOf(InputKind.BUTTON, InputKind.TRIGGER)),
    PRESET_7("Preset 7", setOf(InputKind.BUTTON, InputKind.TRIGGER)),
    PRESET_8("Preset 8", setOf(InputKind.BUTTON, InputKind.TRIGGER));

    /** Número do preset (1..8) ou nulo. */
    val presetNumber: Int?
        get() = if (name.startsWith("PRESET_")) name.removePrefix("PRESET_").toInt() else null

    companion object {
        fun fromName(name: String?): PadAction? = entries.firstOrNull { it.name == name }
        fun preset(n: Int): PadAction = valueOf("PRESET_$n")

        /** Funções que podem ser escolhidas para um botão, na ordem da lista. */
        fun optionsFor(button: PadButton): List<PadAction> = entries.filter { button.kind in it.kinds }
    }
}

/** Lista pronta de funções. O usuário muda só o que quiser. */
object DefaultMapping {
    val map: Map<PadButton, PadAction> = mapOf(
        PadButton.LT to PadAction.ZOOM_OUT_PRESSURE,
        PadButton.RT to PadAction.ZOOM_IN_PRESSURE,
        PadButton.LB to PadAction.SPEED_DOWN,
        PadButton.RB to PadAction.SPEED_UP,
        PadButton.L4 to PadAction.BRIGHT_DOWN,
        PadButton.R4 to PadAction.BRIGHT_UP,
        PadButton.LS to PadAction.STICK_MOVE,
        PadButton.L3 to PadAction.AUTOFOCUS,
        PadButton.RS to PadAction.STICK_FINE,
        PadButton.R3 to PadAction.STOP,
        PadButton.DPAD_UP to PadAction.TILT_UP,
        PadButton.DPAD_RIGHT to PadAction.PAN_RIGHT,
        PadButton.DPAD_DOWN to PadAction.TILT_DOWN,
        PadButton.DPAD_LEFT to PadAction.PAN_LEFT,
        PadButton.A to PadAction.PRESET_1,
        PadButton.B to PadAction.PRESET_2,
        PadButton.X to PadAction.PRESET_3,
        PadButton.Y to PadAction.PRESET_4,
        PadButton.VIEW to PadAction.NONE,
        PadButton.MENU to PadAction.NONE,
        PadButton.HOME to PadAction.NONE,
        PadButton.SCREENSHOT to PadAction.NONE,
        PadButton.M to PadAction.NONE,
    )

    /** Completa um mapeamento salvo (botões que faltam ganham o padrão; funções inválidas para o tipo viram o padrão). */
    fun sanitize(saved: Map<PadButton, PadAction>): Map<PadButton, PadAction> =
        PadButton.entries.associateWith { b ->
            val a = saved[b]
            if (a != null && b.kind in a.kinds) a else map.getValue(b)
        }
}
