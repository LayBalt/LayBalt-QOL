// Cheater Map options ported from OdinClient (https://github.com/skies-starred/OdinClient), BSD-3-Clause, Copyright (c) 2026 Starred
package com.laybalt.features.impl.dungeon.map

import com.laybalt.LayBaltQOL.MOD_ID
import com.laybalt.LayBaltQOL.mc
import com.laybalt.ui.hud.HudElement
import com.laybalt.utils.ColorUtils.colorCodeByPercent
import com.laybalt.utils.ColorUtils.colorizeScore
import com.laybalt.utils.ColorUtils.lerp
import com.laybalt.utils.MathUtils
import com.laybalt.utils.dungeons.DungeonListener
import com.laybalt.utils.dungeons.DungeonPlayer
import com.laybalt.utils.dungeons.enums.DungeonClass
import com.laybalt.utils.dungeons.map.core.*
import com.laybalt.utils.dungeons.map.handlers.*
import com.laybalt.utils.dungeons.map.utils.MapUtils
import com.laybalt.utils.equalsOneOf
import com.laybalt.utils.items.ItemUtils.skyblockId
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.Render2D.drawBorder
import com.laybalt.utils.render.Render2D.drawCenteredString
import com.laybalt.utils.render.Render2D.drawPlayerHead
import com.laybalt.utils.render.Render2D.drawRect
import com.laybalt.utils.render.Render2D.drawTexture
import com.laybalt.utils.render.RenderHelper.renderVec
import gg.essential.universal.UGraphics
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier
import java.awt.Color

object MapRenderer: HudElement() {
    override val name = "Dungeon Map"
    override val toggle get() = DungeonMap.enabled && MapConfig.mapEnabled.value
    override val shouldDraw get() = LocationUtils.inDungeon && (! LocationUtils.inBoss || ! MapConfig.mapHideInBoss.value)

    private val checkmarkGreen = Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/dungeonmap/checkmarks/green_check.png")
    private val checkmarkWhite = Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/dungeonmap/checkmarks/white_check.png")
    private val checkmarkUnknown = Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/dungeonmap/checkmarks/question.png")
    private val checkmarkFail = Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/dungeonmap/checkmarks/cross.png")
    private val ownPlayerMarker = Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/dungeonmap/marker.png")

    override fun draw(ctx: GuiGraphicsExtractor, example: Boolean): Pair<Float, Float> = draw(ctx, example, true)

    fun draw(ctx: GuiGraphicsExtractor, example: Boolean, extraInfo: Boolean): Pair<Float, Float> {
        // NoammAddons 069b8ecb: corner 5 is drawn 1px further in so the layout is centred
        val startX = MapUtils.renderStartX
        val startY = MapUtils.renderStartY
        // NoammAddons 961ca630 / 73177614: the HUD editor shows a sample dungeon, or the live map while in a run
        val preview = example && ! LocationUtils.inDungeon

        renderBackground(ctx)
        ctx.pose().translate(startX, startY)
        if (preview) {
            renderRooms(ctx, MapPreview.tiles, MapPreview.CONNECTOR_SIZE, true)
            renderText(ctx, MapPreview.rooms, MapPreview.CONNECTOR_SIZE, MapUtils.mapRoomSize / 2, true)
        }
        else {
            applyCheater()
            renderRooms(ctx, DungeonScanner.dungeonList, HotbarMapScanner.quarterRoom.takeUnless { it == - 1 } ?: 4, false)
            renderText(ctx, DungeonScanner.uniqueRooms.values, HotbarMapScanner.quarterRoom, HotbarMapScanner.halfRoom, false)
        }
        ctx.pose().translate(- startX, - startY)

        if (preview) MapPreview.players.forEachIndexed { i, player -> drawPlayerHead(ctx, player, isSelf = i == 0) }
        else renderPlayerHeads(ctx)
        if (extraInfo) renderExtraInfo(ctx, example)

        return 128f to if (MapConfig.mapExtraInfo.value) 140f else 128f
    }

    private fun renderBackground(ctx: GuiGraphicsExtractor) {
        val width = 128
        val height = if (MapConfig.mapExtraInfo.value) 140f else 128f

        ctx.drawRect(0, 0, width, height, MapConfig.mapBackground.value)
        ctx.drawBorder(0, 0, width, height, MapConfig.mapBorderColor.value, MapConfig.mapBorderWidth.value)
    }

    private fun renderExtraInfo(ctx: GuiGraphicsExtractor, example: Boolean) {
        if (! MapConfig.mapExtraInfo.value) return
        if (! example && ! MapConfig.dungeonMapCheater.value && ! DungeonListener.dungeonStarted) return

        // NoammAddons 926632a2: compact crypts and the number of bonus bats killed
        val secretsStr = "&6Secrets: &b${ScoreCalculation.foundSecrets}&f/&b${ScoreCalculation.secretsUntil300}&f/&e${DungeonScanner.secretCount}"
        val cryptsStr = colorCodeByPercent(ScoreCalculation.cryptsCount, 6) + "C${ScoreCalculation.cryptsCount}"
        val batsStr = (if (ScoreCalculation.batCount > 0) "&aB" else "&cB") + ScoreCalculation.batCount
        val scoreStr = "&eScore: ${colorizeScore(ScoreCalculation.score)}&r"
        val deathsStr = "&cDeaths: ${colorCodeByPercent(ScoreCalculation.deathCount, 4, true)}${ScoreCalculation.deathCount}&r"
        val bonusStr = buildString {
            append(if (ScoreCalculation.mimicKilled) "&aM &f| " else "&cM &f| ")
            append(if (ScoreCalculation.princeKilled) "&aP" else "&cP")
        }

        val line1 = "$secretsStr    $cryptsStr &f| $batsStr"
        val line2 = "$scoreStr   $deathsStr   $bonusStr"

        ctx.pose().translate(width / 2f, 128f)
        ctx.drawCenteredString(line1, 0f, - 4f, scale = 0.7f)
        ctx.drawCenteredString(line2, 0f, 2f, scale = 0.7f)
    }

    private fun applyCheater() {
        if (! MapConfig.revealHidden) return
        DungeonScanner.dungeonList.forEach { tile ->
            if (tile.state == RoomState.UNOPENED) tile.state = RoomState.UNDISCOVERED
        }
    }

    /** Cheater Map tile darkening (OdinClient CheaterMap): scales RGB by [factor], keeps alpha. */
    private fun darken(color: Color, factor: Float): Color {
        val f = factor.coerceIn(0f, 1f)
        return Color((color.red * f).toInt(), (color.green * f).toInt(), (color.blue * f).toInt(), color.alpha)
    }

    private fun getDoorState(door: DoorTile): RoomState {
        if (door.roomTileIndices.size != 2) return RoomState.UNDISCOVERED
        if (door.roomTiles.any { it.state == RoomState.UNDISCOVERED }) return RoomState.UNDISCOVERED
        return RoomState.UNOPENED
    }

    private fun renderRooms(ctx: GuiGraphicsExtractor, tiles: Array<Tile>, connectorSize: Int, preview: Boolean) {
        for (y in 0 .. 10) for (x in 0 .. 10) {
            val tile = tiles[y * 11 + x].takeUnless { it is Unknown } ?: continue
            val hidden = tile.state == RoomState.UNDISCOVERED || (tile is DoorTile && getDoorState(tile) == RoomState.UNDISCOVERED)
            if (hidden && ! MapConfig.revealHidden) continue

            var color = tile.getColor()
            if (hidden && MapConfig.cheaterDarken.value) {
                color = darken(color, MapConfig.cheaterDarkenFactor.value.toFloat())
            }

            //#if CHEAT
            if (MapConfig.dungeonMapCheater.value && MapConfig.highlightMimicRoom.value && tile is RoomTile && tile.uniqueRoom?.hasMimic == true) {
                color = color.lerp(MapConfig.colorMimic.value, 0.2)
            }
            //#endif

            val xOffset = (x shr 1) * (MapUtils.mapRoomSize + connectorSize)
            val yOffset = (y shr 1) * (MapUtils.mapRoomSize + connectorSize)

            val xEven = x and 1 == 0
            val yEven = y and 1 == 0
            val predicted = getPredictedTypes(tile, y * 11 + x, preview)

            when {
                xEven && yEven -> if (predicted != null) drawPredictedRoom(ctx, xOffset, yOffset, predicted)
                else if (tile is RoomTile) ctx.drawRect(xOffset, yOffset, MapUtils.mapRoomSize, MapUtils.mapRoomSize, color)

                ! xEven && ! yEven -> {
                    ctx.drawRect(
                        xOffset,
                        yOffset, MapUtils.mapRoomSize + connectorSize,
                        MapUtils.mapRoomSize + connectorSize,
                        color
                    )
                }

                else -> drawRoomConnector(
                    ctx, xOffset, yOffset, connectorSize, tile is DoorTile, ! xEven, color
                )
            }

            if (tile is RoomTile && tile.data.isUnknown() && predicted == null) {
                drawRoomCheckmark(ctx, tile, xOffset, yOffset)
            }
        }
    }

    /**
     * NoammAddons 54224635: possible types of an unopened 1x1 (puzzle / trap / miniboss), worked out from the
     * special column of the layout. Only on the legit map; a map that reveals hidden rooms has nothing to predict.
     */
    private fun getPredictedTypes(tile: Tile, index: Int, preview: Boolean): List<RoomType>? {
        if (! MapConfig.predictRooms.value || MapConfig.revealHidden) return null
        if (tile !is RoomTile || tile.state != RoomState.UNOPENED) return null
        if (preview) return MapPreview.predictions[index]
        return SpecialColumn.predict(index)
    }

    private fun drawPredictedRoom(ctx: GuiGraphicsExtractor, x: Int, y: Int, types: List<RoomType>) {
        val roomSize = MapUtils.mapRoomSize
        val colors = types.map {
            when (it) {
                RoomType.CHAMPION -> MapConfig.colorMiniboss
                RoomType.TRAP -> MapConfig.colorTrap
                else -> MapConfig.colorPuzzle
            }.value
        }.let { colors ->
            // NoammAddons bdf5bfa1: a certain prediction keeps the room colour, a guess is darkened
            colors.map { if (colors.size > 1) it.darker().darker() else it }
        }

        ctx.pose().pushMatrix()
        ctx.pose().translate(x.toFloat(), y.toFloat())

        when (colors.size) {
            1 -> ctx.drawRect(0, 0, roomSize, roomSize, colors[0])
            2 -> {
                ctx.drawRect(0, 0, roomSize, roomSize / 2, colors[0])
                ctx.drawRect(0, roomSize / 2, roomSize, roomSize - roomSize / 2, colors[1])
            }

            3 -> {
                val outer = roomSize * 5 / 16
                val center = roomSize - outer
                val inner = center - 1

                ctx.drawRect(0, 0, roomSize, outer, colors[0])
                ctx.drawRect(0, 0, outer, inner, colors[0])
                ctx.drawRect(inner, outer, roomSize - inner, roomSize - outer, colors[1])
                ctx.drawRect(0, inner, roomSize, roomSize - inner, colors[1])
                ctx.drawRect(outer, outer, center - outer, center - outer, colors[2])
            }
        }

        ctx.pose().popMatrix()
    }

    private fun renderText(ctx: GuiGraphicsExtractor, rooms: Collection<UniqueRoom>, connectorSize: Int, halfRoomSize: Int, preview: Boolean) {
        val roomSize = MapUtils.mapRoomSize.toFloat()
        val gapSize = connectorSize.toFloat()
        val halfRoom = halfRoomSize.toFloat()
        val fullCellSize = roomSize + gapSize

        rooms.forEach { unq ->
            val roomTile = unq.mainRoom

            if (unq.data.isUnknown()) return@forEach
            if (MapConfig.hideBloodroomOvelay.value && unq.data.type == RoomType.BLOOD) return@forEach

            // NoammAddons bfdb439f: a scanned room nobody has opened yet shows Hypixel's "?" on the legit map
            val unopenedTile = unq.tiles.find { ! it.isSeparator && it.state == RoomState.UNOPENED }
            if (! MapConfig.revealHidden && unopenedTile != null) {
                val (row, column) = unopenedTile.getGridPos()
                if (getPredictedTypes(unopenedTile, row * 11 + column, preview) != null) return@forEach

                val xOffset = (column shr 1) * (MapUtils.mapRoomSize + connectorSize)
                val yOffset = (row shr 1) * (MapUtils.mapRoomSize + connectorSize)
                drawRoomCheckmark(ctx, unopenedTile, xOffset, yOffset)
                return@forEach
            }

            if (unq.data.type.equalsOneOf(RoomType.ENTRANCE, RoomType.FAIRY)) return@forEach
            val showHiddenNames = MapConfig.revealHidden && MapConfig.cheaterShowNames.value
            if (! showHiddenNames && roomTile.state.equalsOneOf(RoomState.UNDISCOVERED, RoomState.UNOPENED)) return@forEach

            val checkPos = unq.getCheckmarkPosition()
            val cX = (checkPos.first / 2f) * fullCellSize + halfRoom
            val cY = (checkPos.second / 2f) * fullCellSize + halfRoom

            val color = when (roomTile.state) {
                RoomState.GREEN -> Color(85, 255, 85)
                RoomState.FAILED -> Color(255, 0, 0)
                RoomState.CLEARED -> Color(255, 255, 255)
                else -> Color(170, 170, 170)
            }

            val checkmarkStyle = when (MapConfig.dungeonMapCheckmarkStyle.value) {
                4 -> if (roomTile.data.type == RoomType.PUZZLE || roomTile.data.secrets == 0) 2 else 1
                else -> MapConfig.dungeonMapCheckmarkStyle.value
            }

            when (checkmarkStyle) {
                2, 3 -> {
                    var scale = MapConfig.textScale.value.toFloat()
                    val showSecrets = checkmarkStyle == 3 && roomTile.data.secrets > 0

                    if (MapConfig.limitRoomNameSize.value) {
                        unq.updateBounds(roomSize, gapSize)
                        val secretsText = if (showSecrets) "${unq.foundSecrets}/${roomTile.data.secrets}" else ""
                        val maxLineW = unq.updateTextScale(1f, showSecrets, secretsText)
                        var totalH = unq.cacheSplitName.size * UGraphics.getFontHeight().toFloat()
                        if (showSecrets) totalH += UGraphics.getFontHeight()

                        if (maxLineW > 0 && totalH > 0) {
                            val sW = unq.cachedMaxWidth / maxLineW
                            val sH = unq.cachedMaxHeight / totalH
                            scale = sW.coerceAtMost(sH).coerceIn(0.39f, MapConfig.textScale.value.toFloat())
                        }
                    }

                    val totalLines = unq.cacheSplitName.size + (if (showSecrets) 1 else 0)
                    val totalH = totalLines * UGraphics.getFontHeight() * scale

                    var currentY = cY - totalH / 2

                    for (line in unq.cacheSplitName) {
                        ctx.drawCenteredString(line, cX, currentY, color, scale)
                        currentY += totalH / totalLines
                    }

                    if (showSecrets) {
                        val secStr = "${unq.foundSecrets}/${roomTile.data.secrets}"
                        ctx.drawCenteredString(secStr, cX, currentY, color, scale)
                    }
                }

                1 -> ctx.drawCenteredString(
                    if (roomTile.data.secrets == 0) "0" else "${unq.foundSecrets}/${roomTile.data.secrets}",
                    cX,
                    cY - UGraphics.getFontHeight() / 2,
                    color,
                    MapConfig.textScale.value
                )

                0 -> {
                    val checkmarkSize = MapConfig.checkmarkSize.value * 10
                    val halfcheckmarkSize = checkmarkSize / 2
                    drawCheckmark(ctx, unq.mainRoom, cX - halfcheckmarkSize, cY - halfcheckmarkSize, MapConfig.checkmarkSize.value * 10)
                }
            }
        }
    }

    private fun renderPlayerHeads(ctx: GuiGraphicsExtractor) {
        if (LocationUtils.inBoss) return

        DungeonListener.dungeonTeammatesNoSelf.forEach { player ->
            if (player.isDead) return@forEach
            drawPlayerHead(ctx, player)
        }

        drawPlayerHead(ctx, DungeonListener.thePlayer ?: return)
    }


    private fun drawCheckmark(ctx: GuiGraphicsExtractor, tile: Tile, x: Number, y: Number, size: Number) {
        val checkmark = when (tile.state) {
            RoomState.CLEARED -> checkmarkWhite
            RoomState.GREEN -> checkmarkGreen
            RoomState.FAILED -> checkmarkFail
            RoomState.UNOPENED -> if (! MapConfig.hideQuestionCheckmarks.value) checkmarkUnknown else return
            else -> return
        }

        ctx.drawTexture(checkmark, x, y, size, size)
    }

    /** Draws [tile]'s checkmark centred in the room cell at [xOffset], [yOffset]. */
    private fun drawRoomCheckmark(ctx: GuiGraphicsExtractor, tile: Tile, xOffset: Int, yOffset: Int) {
        val checkmarkSize = MapConfig.checkmarkSize.value * 10
        drawCheckmark(
            ctx, tile,
            xOffset + MapUtils.mapRoomSize / 2 - checkmarkSize / 2,
            yOffset + MapUtils.mapRoomSize / 2 - checkmarkSize / 2,
            checkmarkSize,
        )
    }

    private fun drawPlayerHead(ctx: GuiGraphicsExtractor, teammate: DungeonPlayer, isSelf: Boolean = teammate == DungeonListener.thePlayer) {
        val entity = teammate.entity

        val (x, z, yaw) = if (entity == null || ! entity.isAlive) {
            Triple(teammate.mapX, teammate.mapZ, teammate.yaw)
        }
        else {
            val (mx, mz) = MapUtils.coordsToMap(entity.renderVec)
            Triple(mx, mz, entity.yRot)
        }

        val borderColor = if (MapConfig.mapPlayerHeadColorClassBased.value) teammate.clazz.color
        else MapConfig.mapPlayerHeadColor.value

        val nameColor = if (MapConfig.mapPlayerNameClassColorBased.value && teammate.clazz != DungeonClass.Empty) teammate.clazz.color
        else Color.WHITE

        ctx.pose().pushMatrix()
        ctx.pose().translate(x, z)
        val currentYaw = MathUtils.normalizeYaw(yaw)
        val headYaw = Math.toRadians((currentYaw + 180).toDouble()).toFloat()

        ctx.pose().rotate(headYaw)
        ctx.pose().scale(MapConfig.playerHeadScale.value)

        if (MapConfig.mapVanillaMarker.value && isSelf) {
            ctx.drawTexture(ownPlayerMarker, - 6, - 6, 12, 12, MapConfig.mapVanillaMarkerColor.value)
        }
        else {
            // Odin 8580eb4e: adjustable border (class colour) thickness, 0 hides it
            val border = MapConfig.playerHeadBorderSize.value
            if (border > 0) ctx.drawBorder(- (6 + border), - (6 + border), 12 + 2 * border, 12 + 2 * border, borderColor, border)
            ctx.drawPlayerHead(- 6, - 6, 12, teammate.skin)
        }

        val heldItem = mc.player?.mainHandItem
        val shouldDrawName = MapConfig.playerNames.value == 2 || (MapConfig.playerNames.value == 1
            && (heldItem != null && (heldItem.skyblockId == "SPIRIT_LEAP" || heldItem.skyblockId == "INFINITE_SPIRIT_LEAP"
            || heldItem.skyblockId == "HAUNT_ABILITY")))

        if (shouldDrawName) {
            ctx.pose().rotate(- headYaw)
            ctx.pose().translate(0f, 8f)
            ctx.pose().scale(MapConfig.playerNameScale.value)
            ctx.drawCenteredString(teammate.name, 0, 0, nameColor)
        }

        ctx.pose().popMatrix()
    }

    private fun drawRoomConnector(
        matrices: GuiGraphicsExtractor, x: Int, y: Int, doorWidth: Int, doorway: Boolean, vertical: Boolean, color: Color,
    ) {
        // NoammAddons b57e9e74: adjustable door width (6 matches the old fixed size)
        val doorHeightSize = MapConfig.doorHeightSize.value
        val doorwayOffset = (MapUtils.mapRoomSize - doorHeightSize) / 2
        val doorHeight = if (doorway) doorHeightSize else MapUtils.mapRoomSize
        var x1 = if (vertical) x + MapUtils.mapRoomSize else x
        var y1 = if (vertical) y else y + MapUtils.mapRoomSize
        if (doorway) if (vertical) y1 += doorwayOffset else x1 += doorwayOffset

        matrices.drawRect(
            x1.toDouble(),
            y1.toDouble(),
            (if (vertical) doorWidth else doorHeight).toDouble(),
            (if (vertical) doorHeight else doorWidth).toDouble(),
            color
        )
    }
}
