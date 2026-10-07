package com.laybalt.event.impl

import com.laybalt.event.Event
import com.laybalt.utils.PetUtils.Pet

abstract class PetEvent(val pet: Pet?): Event(cancelable = false) {
    class Change(pet: Pet?, val cause: Cause): PetEvent(pet)
    class LevelUp(pet: Pet): PetEvent(pet)

    enum class Cause { SUMMON, DESPAWN, AUTOPET, MENU }
}