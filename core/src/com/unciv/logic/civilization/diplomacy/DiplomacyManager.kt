package com.unciv.logic.civilization.diplomacy

import com.badlogic.gdx.graphics.Color
import com.unciv.Constants
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeType
import com.unciv.models.Counter
import com.unciv.models.gamebasics.GameBasics
import com.unciv.models.gamebasics.tile.TileResource
import com.unciv.models.gamebasics.tr

enum class RelationshipLevel{
    Unforgivable,
    Enemy,
    Competitor,
    Neutral,
    Favorable,
    Friend,
    Ally
}

enum class DiplomacyFlags{
    DeclinedLuxExchange,
    DeclinedPeace,
    DeclaredWar,
    DeclarationOfFriendship,
    Denunceation,
    BorderConflict
}

enum class DiplomaticModifiers{
    DeclaredWarOnUs,
    WarMongerer,
    CapturedOurCities,
    DeclaredFriendshipWithOurEnemies,
    BetrayedDeclarationOfFriendship,
    Denunciation,
    DenouncedOurAllies,

    YearsOfPeace,
    SharedEnemy,
    DeclarationOfFriendship,
    DeclaredFriendshipWithOurAllies,
    DenouncedOurEnemies,
    OpenBorders
}

class DiplomacyManager() {
    @Transient lateinit var civInfo: CivilizationInfo
    // since this needs to be checked a lot during travel, putting it in a transient is a good performance booster
    @Transient var hasOpenBorders=false

    lateinit var otherCivName:String
    var trades = ArrayList<Trade>()
    var diplomaticStatus = DiplomaticStatus.War

    /** Contains various flags (declared war, promised to not settle, declined luxury trade) and the number of turns in which they will expire.
     *  The JSON serialize/deserialize REFUSES to deserialize hashmap keys as Enums, so I'm forced to use strings instead =(
     *  This is so sad Alexa play Despacito */
    private var flagsCountdown = HashMap<String,Int>()

    /** For AI. Positive is good relations, negative is bad.
     * Baseline is 1 point for each turn of peace - so declaring a war upends 40 years of peace, and e.g. capturing a city can be another 30 or 40.
     * As for why it's String and not DiplomaticModifier see FlagsCountdown comment */
    var diplomaticModifiers = HashMap<String,Float>()

    /** For city-states. Influence is saved in the CITY STATE -> major civ Diplomacy, NOT in the major civ -> cty state diplomacy. */
    var influence = 0f

    fun clone(): DiplomacyManager {
        val toReturn = DiplomacyManager()
        toReturn.otherCivName=otherCivName
        toReturn.diplomaticStatus=diplomaticStatus
        toReturn.trades.addAll(trades.map { it.clone() })
        toReturn.influence = influence
        toReturn.flagsCountdown.putAll(flagsCountdown)
        toReturn.hasOpenBorders=hasOpenBorders
        toReturn.diplomaticModifiers.putAll(diplomaticModifiers)
        return toReturn
    }

    constructor(civilizationInfo: CivilizationInfo, OtherCivName:String) : this() {
        civInfo=civilizationInfo
        otherCivName=OtherCivName
        updateHasOpenBorders()
    }

    //region pure functions
    fun otherCiv() = civInfo.gameInfo.getCivilization(otherCivName)
    fun otherCivDiplomacy() = otherCiv().getDiplomacyManager(civInfo)

    fun turnsToPeaceTreaty(): Int {
        for(trade in trades)
            for(offer in trade.ourOffers)
                if(offer.name == Constants.peaceTreaty && offer.duration > 0) return offer.duration
        return 0
    }

    fun opinionOfOtherCiv() = diplomaticModifiers.values.sum()

    fun relationshipLevel(): RelationshipLevel {
        if(civInfo.isPlayerCivilization() && otherCiv().isPlayerCivilization())
            return RelationshipLevel.Neutral // People make their own choices.

        if(civInfo.isPlayerCivilization())
            return otherCiv().getDiplomacyManager(civInfo).relationshipLevel()

        if(civInfo.isCityState()){
            if (influence<=-60) return RelationshipLevel.Unforgivable
            if (influence<=-30) return RelationshipLevel.Enemy

            if(civInfo.isAtWarWith(otherCiv()))
                return RelationshipLevel.Enemy // See below, same with major civs

            if(influence>=60) return RelationshipLevel.Ally
            if(influence>=30) return RelationshipLevel.Friend
            return RelationshipLevel.Neutral
        }

        // not entirely sure what to do between AI civs, because they probably have different views of each other,
        // maybe we need to average their views of each other? That makes sense to me.

        val opinion = opinionOfOtherCiv()
        if(opinion<=-80) return RelationshipLevel.Unforgivable
        if(opinion<=-40) return RelationshipLevel.Enemy

        // This is here because when you're at war you can either be enemy OR unforgivable,
        // depending on the opinion
        if(civInfo.isAtWarWith(otherCiv()))
            return RelationshipLevel.Enemy

        if(opinion<=-15) return RelationshipLevel.Competitor
        if(opinion>=80) return RelationshipLevel.Ally
        if(opinion>=40) return RelationshipLevel.Friend
        if(opinion>=15) return RelationshipLevel.Favorable
        return RelationshipLevel.Neutral
    }

    fun canDeclareWar() = (turnsToPeaceTreaty()==0 && diplomaticStatus != DiplomaticStatus.War)


    fun goldPerTurn():Int{
        var goldPerTurnForUs = 0
        for(trade in trades) {
            for (offer in trade.ourOffers.filter { it.type == TradeType.Gold_Per_Turn })
                goldPerTurnForUs -= offer.amount
            for (offer in trade.theirOffers.filter { it.type == TradeType.Gold_Per_Turn })
                goldPerTurnForUs += offer.amount
        }
        return goldPerTurnForUs
    }

    fun resourcesFromTrade(): Counter<TileResource> {
        val counter = Counter<TileResource>()
        for(trade in trades){
            for(offer in trade.ourOffers)
                if(offer.type== TradeType.Strategic_Resource || offer.type== TradeType.Luxury_Resource)
                    counter.add(GameBasics.TileResources[offer.name]!!,-offer.amount)
            for(offer in trade.theirOffers)
                if(offer.type== TradeType.Strategic_Resource || offer.type== TradeType.Luxury_Resource)
                    counter.add(GameBasics.TileResources[offer.name]!!,offer.amount)
        }
        for(tradeRequest in otherCiv().tradeRequests.filter { it.requestingCiv==civInfo.civName }){
            for(offer in tradeRequest.trade.theirOffers) // "theirOffers" in the other civ's trade request, is actually out civ's offers
                if(offer.type== TradeType.Strategic_Resource || offer.type== TradeType.Luxury_Resource)
                    counter.add(GameBasics.TileResources[offer.name]!!,-offer.amount)
        }
        return counter
    }
    //endregion

    //region state-changing functions
    fun removeUntenebleTrades(){
        val negativeCivResources = civInfo.getCivResources().filter { it.value<0 }.map { it.key.name }
        for(trade in trades.toList()) {
            for (offer in trade.ourOffers) {
                if (offer.type in listOf(TradeType.Luxury_Resource, TradeType.Strategic_Resource)
                    && offer.name in negativeCivResources){
                    trades.remove(trade)
                    val otherCivTrades = otherCiv().getDiplomacyManager(civInfo).trades
                    otherCivTrades.removeAll{ it.equals(trade.reverse()) }
                    civInfo.addNotification("One of our trades with [$otherCivName] has been cut short!".tr(),null, Color.GOLD)
                    otherCiv().addNotification("One of our trades with [${civInfo.civName}] has been cut short!".tr(),null, Color.GOLD)
                }
            }
        }
    }

    // for performance reasons we don't want to call this every time we want to see if a unit can move through a tile
    fun updateHasOpenBorders(){
        var newHasOpenBorders = false
        for(trade in trades) {
            for (offer in trade.theirOffers)
                if (offer.name == "Open Borders" && offer.duration > 0) {
                    newHasOpenBorders = true
                    break
                }
            if(newHasOpenBorders) break
        }

        if(hasOpenBorders && !newHasOpenBorders){ // borders were closed, get out!
            for(unit in civInfo.getCivUnits().filter { it.currentTile.getOwner()?.civName== otherCivName }){
                unit.movementAlgs().teleportToClosestMoveableTile()
            }
        }

        hasOpenBorders=newHasOpenBorders
    }

    fun nextTurn(){
        for(trade in trades.toList()){
            for(offer in trade.ourOffers.union(trade.theirOffers).filter { it.duration>0 })
                offer.duration--

            if(trade.ourOffers.all { it.duration<=0 } && trade.theirOffers.all { it.duration<=0 }) {
                trades.remove(trade)
                civInfo.addNotification("One of our trades with [$otherCivName] has ended!".tr(),null, Color.YELLOW)
            }
        }
        removeUntenebleTrades()
        updateHasOpenBorders()

        if(diplomaticStatus==DiplomaticStatus.Peace) {
            if(diplomaticModifiers[DiplomaticModifiers.YearsOfPeace.name]!! < 30)
                addModifier(DiplomaticModifiers.YearsOfPeace, 0.5f)
        }
        else revertToZero(DiplomaticModifiers.YearsOfPeace,-0.5f) // war makes you forget the good ol' days

        var openBorders = 0
        if(hasOpenBorders) openBorders+=1

        if(otherCivDiplomacy().hasOpenBorders) openBorders+=1
        if(openBorders>0) addModifier(DiplomaticModifiers.OpenBorders,openBorders/8f) // so if we both have open borders it'll grow by 0.25 per turn
        else revertToZero(DiplomaticModifiers.OpenBorders, 1/8f)

        revertToZero(DiplomaticModifiers.DeclaredWarOnUs,1/8f) // this disappears real slow - it'll take 160 turns to really forget, this is war declaration we're talking about
        revertToZero(DiplomaticModifiers.WarMongerer,1/2f) // warmongering gives a big negative boost when it happens but they're forgotten relatively quickly, like WWII amirite
        revertToZero(DiplomaticModifiers.CapturedOurCities,1/4f) // if you captured our cities, though, that's harder to forget
        revertToZero(DiplomaticModifiers.BetrayedDeclarationOfFriendship,1/2f) // if you captured our cities, though, that's harder to forget
        if(!hasFlag(DiplomacyFlags.DeclarationOfFriendship))
            revertToZero(DiplomaticModifiers.DeclarationOfFriendship, 1/2f) //decreases slowly and will revert to full if it is declared later

        for(flag in flagsCountdown.keys.toList()) {
            flagsCountdown[flag] = flagsCountdown[flag]!! - 1
            if(flagsCountdown[flag]==0) {
                flagsCountdown.remove(flag)
            }
        }

        if (influence > 1) {
            influence -= 1
        } else if (influence < 1) {
            influence += 1
        } else influence = 0f

    }

    /** Everything that happens to both sides equally when war is delcared by one side on the other */
    private fun onWarDeclared(){
        diplomaticStatus = DiplomaticStatus.War

        // Cancel all trades.
        for(trade in trades)
            for(offer in trade.theirOffers.filter { it.duration>0 })
                civInfo.addNotification("["+offer.name+"] from [$otherCivName] has ended",null, Color.GOLD)
        trades.clear()
        updateHasOpenBorders()

        setFlag(DiplomacyFlags.DeclinedPeace,10)/// AI won't propose peace for 10 turns
        setFlag(DiplomacyFlags.DeclaredWar,10) // AI won't agree to trade for 10 turns
        removeFlag(DiplomacyFlags.BorderConflict)
    }

    fun declareWar(){
        val otherCiv = otherCiv()
        val otherCivDiplomacy = otherCivDiplomacy()

        onWarDeclared()
        otherCivDiplomacy.onWarDeclared()

        otherCiv.addNotification("[${civInfo.civName}] has declared war on us!",null, Color.RED)
        otherCiv.popupAlerts.add(PopupAlert(AlertType.WarDeclaration,civInfo.civName))

        otherCivDiplomacy.setModifier(DiplomaticModifiers.DeclaredWarOnUs,-20f)
        if(otherCiv.isCityState()) otherCivDiplomacy.influence -= 60

        for(thirdCiv in civInfo.getKnownCivs()){
            if(thirdCiv.isAtWarWith(otherCiv))
                thirdCiv.getDiplomacyManager(civInfo).addModifier(DiplomaticModifiers.WarMongerer,5f)
            else thirdCiv.getDiplomacyManager(civInfo).addModifier(DiplomaticModifiers.WarMongerer,-5f)
        }

        if(hasFlag(DiplomacyFlags.DeclarationOfFriendship)){
            removeFlag(DiplomacyFlags.DeclarationOfFriendship)
            otherCivDiplomacy.removeModifier(DiplomaticModifiers.DeclarationOfFriendship)
            for(knownCiv in civInfo.getKnownCivs()){
                val amount = if(knownCiv==otherCiv) -40f else -20f
                val diploManager = knownCiv.getDiplomacyManager(civInfo)
                diploManager.addModifier(DiplomaticModifiers.BetrayedDeclarationOfFriendship, amount)
                diploManager.removeModifier(DiplomaticModifiers.DeclaredFriendshipWithOurAllies) // obviously this guy's declarations of friendship aren't worth much.
            }
        }
        otherCivDiplomacy.removeFlag(DiplomacyFlags.DeclarationOfFriendship)
    }

    fun makePeace(){
        diplomaticStatus= DiplomaticStatus.Peace
        val otherCiv = otherCiv()
        // We get out of their territory
        for(unit in civInfo.getCivUnits().filter { it.getTile().getOwner()== otherCiv})
            unit.movementAlgs().teleportToClosestMoveableTile()

        // And we get out of theirs
        for(unit in otherCiv.getCivUnits().filter { it.getTile().getOwner()== civInfo})
            unit.movementAlgs().teleportToClosestMoveableTile()
    }

    fun hasFlag(flag:DiplomacyFlags) = flagsCountdown.containsKey(flag.toString())
    fun setFlag(flag: DiplomacyFlags, amount: Int){ flagsCountdown[flag.toString()]=amount}
    fun removeFlag(flag: DiplomacyFlags){ flagsCountdown.remove(flag.toString())}

    fun addModifier(modifier: DiplomaticModifiers, amount:Float){
        val modifierString = modifier.toString()
        if(!hasModifier(modifier)) setModifier(modifier,0f)
        diplomaticModifiers[modifierString] = diplomaticModifiers[modifierString]!!+amount
        if(diplomaticModifiers[modifierString]==0f) diplomaticModifiers.remove(modifierString)
    }

    fun setModifier(modifier: DiplomaticModifiers, amount: Float){
        val modifierString = modifier.toString()
        diplomaticModifiers[modifierString] = amount
    }

    fun removeModifier(modifier: DiplomaticModifiers) =
        diplomaticModifiers.remove(modifier.toString())

    fun hasModifier(modifier: DiplomaticModifiers) = diplomaticModifiers.containsKey(modifier.toString())

    /** @param amount always positive, so you don't need to think about it */
    fun revertToZero(modifier: DiplomaticModifiers, amount: Float){
        if(!hasModifier(modifier)) return
        val currentAmount = diplomaticModifiers[modifier.toString()]!!
        if(currentAmount > 0) addModifier(modifier,-amount)
        else addModifier(modifier,amount)
    }

    fun signDeclarationOfFriendship(){
        setModifier(DiplomaticModifiers.DeclarationOfFriendship,35f)
        otherCivDiplomacy().setModifier(DiplomaticModifiers.DeclarationOfFriendship,35f)
        setFlag(DiplomacyFlags.DeclarationOfFriendship,30)
        otherCivDiplomacy().setFlag(DiplomacyFlags.DeclarationOfFriendship,30)

        for(thirdCiv in civInfo.getKnownCivs().filter { it.isMajorCiv() }){
            if(thirdCiv==otherCiv() || !thirdCiv.knows(otherCivName)) continue
            val thirdCivRelationshipWithOtherCiv = thirdCiv.getDiplomacyManager(otherCiv()).relationshipLevel()
            when(thirdCivRelationshipWithOtherCiv){
                RelationshipLevel.Unforgivable -> addModifier(DiplomaticModifiers.DeclaredFriendshipWithOurEnemies,-15f)
                RelationshipLevel.Enemy -> addModifier(DiplomaticModifiers.DeclaredFriendshipWithOurEnemies,-5f)
                RelationshipLevel.Friend -> addModifier(DiplomaticModifiers.DeclaredFriendshipWithOurAllies,5f)
                RelationshipLevel.Ally -> addModifier(DiplomaticModifiers.DeclaredFriendshipWithOurAllies,15f)
            }
        }
    }
    
    fun denounce(){
        setModifier(DiplomaticModifiers.Denunciation,-35f)
        otherCivDiplomacy().setModifier(DiplomaticModifiers.Denunciation,-35f)
        setFlag(DiplomacyFlags.Denunceation,30)
        otherCivDiplomacy().setFlag(DiplomacyFlags.Denunceation,30)

        for(thirdCiv in civInfo.getKnownCivs().filter { it.isMajorCiv() }){
            if(thirdCiv==otherCiv() || !thirdCiv.knows(otherCivName)) continue
            val thirdCivRelationshipWithOtherCiv = thirdCiv.getDiplomacyManager(otherCiv()).relationshipLevel()
            when(thirdCivRelationshipWithOtherCiv){
                RelationshipLevel.Unforgivable -> addModifier(DiplomaticModifiers.DenouncedOurEnemies,15f)
                RelationshipLevel.Enemy -> addModifier(DiplomaticModifiers.DenouncedOurEnemies,5f)
                RelationshipLevel.Friend -> addModifier(DiplomaticModifiers.DenouncedOurAllies,-5f)
                RelationshipLevel.Ally -> addModifier(DiplomaticModifiers.DenouncedOurAllies,-15f)
            }
        }
    }
    //endregion
}
