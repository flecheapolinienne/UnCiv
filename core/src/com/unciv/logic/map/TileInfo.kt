package com.unciv.logic.map

import com.badlogic.gdx.math.Vector2
import com.unciv.logic.city.CityInfo
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.models.gamebasics.GameBasics
import com.unciv.models.gamebasics.Terrain
import com.unciv.models.gamebasics.TileImprovement
import com.unciv.models.gamebasics.TileResource
import com.unciv.models.linq.Linq
import com.unciv.models.stats.Stats

class TileInfo {
    @Transient @JvmField var tileMap: TileMap? = null

    @JvmField var unit: MapUnit? = null
    @JvmField var position: Vector2 = Vector2.Zero
    @JvmField var baseTerrain: String? = null
    @JvmField var terrainFeature: String? = null
    @JvmField var resource: String? = null
    @JvmField var improvement: String? = null
    @JvmField var improvementInProgress: String? = null
    @JvmField var owner: String? = null // owning civ name
    @JvmField var workingCity: String? = null // Working City name
    @JvmField var roadStatus = RoadStatus.None
    @JvmField var explored = false
    @JvmField var turnsToImprovement: Int = 0

    val city: CityInfo?
        get() = if (workingCity == null) null else getOwner()!!.cities.first { it.name == workingCity }

    val lastTerrain: Terrain
        get() = if (terrainFeature == null) getBaseTerrain() else getTerrainFeature()!!

    val tileResource: TileResource
        get() = if (resource == null) throw Exception("No resource exists for this tile!") else GameBasics.TileResources[resource]!!

    val isCityCenter: Boolean
        get() = city != null && position == city!!.cityLocation

    val tileImprovement: TileImprovement?
        get() = if (improvement == null) null else GameBasics.TileImprovements[improvement]

    val neighbors: Linq<TileInfo>
        get() = tileMap!!.getTilesAtDistance(position, 1)

    val height: Int
        get() {
            var height = 0
            if (Linq("Forest", "Jungle").contains(terrainFeature)) height += 1
            if ("Hill" == baseTerrain) height += 2
            return height
        }

    fun getBaseTerrain(): Terrain {
        return GameBasics.Terrains[baseTerrain]!!
    }

    fun getOwner(): CivilizationInfo? {
        return if (owner == null) null
        else tileMap!!.gameInfo!!.civilizations.first { it.civName == owner }
    }

    fun getTerrainFeature(): Terrain? {
        return if (terrainFeature == null) null else GameBasics.Terrains[terrainFeature]
    }


    fun getTileStats(observingCiv: CivilizationInfo): Stats {
        return getTileStats(city, observingCiv)
    }

    fun getTileStats(city: CityInfo?, observingCiv: CivilizationInfo): Stats {
        var stats = getBaseTerrain().clone()

        if (terrainFeature != null) {
            val terrainFeature = getTerrainFeature()
            if (terrainFeature!!.overrideStats)
                stats = terrainFeature.clone()
            else
                stats.add(terrainFeature)
        }

        if (hasViewableResource(observingCiv)) {
            val resource = tileResource
            stats.add(tileResource) // resource base
            if (resource.building != null && city != null && city.cityConstructions.isBuilt(resource.building!!)) {
                stats.add(resource.GetBuilding()!!.resourceBonusStats!!) // resource-specific building (eg forge, stable) bonus
            }
        }

        val improvement = tileImprovement
        if (improvement != null) {
            if (resource != null && tileResource.improvement == improvement.name)
                stats.add(tileResource.improvementStats!!) // resource-specifc improvement
            else
                stats.add(improvement) // basic improvement

            if (improvement.improvingTech != null && observingCiv.tech.isResearched(improvement.improvingTech!!)) stats.add(improvement.improvingTechStats!!) // eg Chemistry for mines
            if (improvement.name == "Trading post" && city != null && city.civInfo.policies.isAdopted("Free Thought"))
                stats.science += 1f
            if (Linq("Academy", "Landmark", "Manufactory", "Customs House").contains(improvement.name) && observingCiv.policies.isAdopted("Freedom Complete"))
                stats.add(improvement) // again, for the double effect
        }

        if (isCityCenter) {
            if (stats.food < 2) stats.food = 2f
            if (stats.production < 1) stats.production = 1f
        }

        if (stats.production < 0) stats.production = 0f

        if ("Jungle" == terrainFeature && city != null
                && city.buildingUniques.contains("JunglesProvideScience"))
            stats.science += 2f
        if (stats.gold != 0f && observingCiv.goldenAges.isGoldenAge())
            stats.gold++

        return stats
    }

    fun canBuildImprovement(improvement: TileImprovement, civInfo: CivilizationInfo): Boolean {
        if (isCityCenter || improvement.name == this.improvement) return false
        val topTerrain = if (terrainFeature == null) getBaseTerrain() else getTerrainFeature()
        if (improvement.techRequired != null && !civInfo.tech.isResearched(improvement.techRequired!!)) return false
        if (improvement.terrainsCanBeBuiltOn.contains(topTerrain!!.name)) return true
        if (improvement.name == "Road" && this.roadStatus === RoadStatus.None) return true
        if (improvement.name == "Railroad" && this.roadStatus !== RoadStatus.Railroad) return true
        if (topTerrain.unbuildable) return false
        return hasViewableResource(civInfo) && tileResource.improvement == improvement.name

    }

    fun startWorkingOnImprovement(improvement: TileImprovement, civInfo: CivilizationInfo) {
        improvementInProgress = improvement.name
        turnsToImprovement = improvement.getTurnsToBuild(civInfo)
    }

    fun stopWorkingOnImprovement() {
        improvementInProgress = null
    }

    fun nextTurn() {
        if (unit != null) unit!!.nextTurn(this)
    }

    override fun toString(): String {
        val SB = StringBuilder()
        if (isCityCenter) {
            SB.appendln(workingCity + ",\r\n" + city!!.cityConstructions.getProductionForTileInfo())
        }
        SB.appendln(this.baseTerrain)
        if (terrainFeature != null) SB.appendln(terrainFeature!!)
        if (hasViewableResource(tileMap!!.gameInfo!!.getPlayerCivilization())) SB.appendln(resource!!)
        if (roadStatus !== RoadStatus.None && !isCityCenter) SB.appendln(roadStatus)
        if (improvement != null) SB.appendln(improvement!!)
        if (improvementInProgress != null) SB.appendln("$improvementInProgress in ${this.turnsToImprovement} turns")
        if (unit != null) SB.appendln(unit!!.name + "(" + unit!!.movementString + ")")
        return SB.toString()
    }

    fun hasViewableResource(civInfo: CivilizationInfo): Boolean {
        return resource != null && (tileResource.revealedBy == null || civInfo.tech.isResearched(tileResource.revealedBy!!))
    }

    fun hasIdleUnit(): Boolean {
        if (unit == null) return false
        if (unit!!.currentMovement == 0f) return false
        if (unit!!.name == "Worker" && improvementInProgress != null) return false
        return true
    }

    fun moveUnitToTile(otherTile: TileInfo, movementDistance: Float) {
        if (otherTile.unit != null) return  // Fail.
        unit!!.currentMovement -= movementDistance
        if (unit!!.currentMovement < 0.1) unit!!.currentMovement = 0f // silly floats which are "almost zero"
        otherTile.unit = unit
        unit = null
    }
}