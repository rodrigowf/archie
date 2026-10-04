package com.assistant.peripheral.service

import com.assistant.core.voicehost.trigger.RecentsKeyAccessibilityService

/**
 * Recents long-press trigger at the OLD FQCN (spec 14 §1.7): Android keys the user's
 * accessibility grant to this component name, so renaming it would silently drop the grant after
 * the upgrade. Behaviour (600 ms hold → `TriggerIngress`, gated by `enable_button_trigger`) is
 * :core:voice-host's [RecentsKeyAccessibilityService].
 */
class ButtonAccessibilityService : RecentsKeyAccessibilityService()
