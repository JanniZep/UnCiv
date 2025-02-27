package com.unciv.logic.map

import com.unciv.models.gamebasics.GameBasics
import com.unciv.models.gamebasics.unit.Promotion

class UnitPromotions{
    @Transient lateinit var unit:MapUnit
    var XP=0
    var promotions = HashSet<String>()
    // The number of times this unit has been promoted
    // some promotions don't come from being promoted but from other things,
    // like from being constructed in a specific city etc.
    var numberOfPromotions = 0

    fun xpForNextPromotion() = (numberOfPromotions+1)*10
    fun canBePromoted() = XP >= xpForNextPromotion()

    fun addPromotion(promotionName:String, isFree:Boolean = false){
        if (!isFree) {
            XP -= xpForNextPromotion()
            numberOfPromotions++
        }

        if(promotionName=="Heal Instantly"){
            unit.health += 50
            if(unit.health>100) unit.health=100
        }
        else promotions.add(promotionName)

        unit.updateUniques()
        unit.updateViewableTiles()  // some promotions/uniques give the unit bonus sight
    }

    fun getAvailablePromotions(): List<Promotion> {
        return GameBasics.UnitPromotions.values
                .filter { unit.type.toString() in it.unitTypes && it.name !in promotions }
                .filter { it.prerequisites.isEmpty() || it.prerequisites.any { p->p in promotions } }
    }

    fun clone(): UnitPromotions {
        val toReturn = UnitPromotions()
        toReturn.XP=XP
        toReturn.promotions.addAll(promotions)
        toReturn.numberOfPromotions=numberOfPromotions
        return toReturn
    }

    fun totalXpProduced(): Int {
        var sum = XP
        for(i in 1..numberOfPromotions) sum += 10*i
        return sum
    }

}