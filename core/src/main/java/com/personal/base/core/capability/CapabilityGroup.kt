package com.personal.base.core.capability

/**
 * What a capability is for.
 *
 * The platform has far more capabilities than anyone can hold in their head at once, so they are
 * grouped for the console and for anything else that has to present them. The grouping lives here
 * rather than in a front end so it cannot drift: a capability carries its own group, and every
 * view is built from that.
 */
enum class CapabilityGroup(val displayName: String, val blurb: String) {
    COMMUNICATION("Communication", "Messages, calls, and the people behind them"),
    CAPTURE("Camera & Audio", "Anything that records what the world looks or sounds like"),
    DISPLAY("Display & Alerts", "What the phone shows and how it gets your attention"),
    CONNECTIVITY("Connectivity & Files", "Networks, radios, and moving data around"),
    DEVICE("Device & System", "Control, identity, and the machinery underneath"),
    DATA("Data & Automation", "Everything the device knows, and what it does with it")
}