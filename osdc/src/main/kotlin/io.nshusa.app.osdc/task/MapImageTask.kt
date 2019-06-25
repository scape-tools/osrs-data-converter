package io.nshusa.app.osdc.task

import io.nshusa.app.osdc.util.Dialogue
import javafx.application.Platform
import javafx.concurrent.Task
import net.openrs.cache.Cache
import net.openrs.cache.Container
import net.openrs.cache.FileStore
import net.openrs.cache.region.Region
import net.openrs.cache.sprite.Sprites
import net.openrs.cache.sprite.Textures
import net.openrs.cache.type.TypeListManager
import net.openrs.cache.util.XTEAManager
import java.awt.Color
import java.awt.Image
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.ArrayList
import java.util.HashMap
import javax.imageio.ImageIO

class MapImageTask(val cachePath: Path, val xteaPath: Path, val output: Path) : Task<Boolean>() {

    override fun call(): Boolean {
        try {
            Cache(FileStore.open(cachePath.toFile())).use { cache ->
                init(cache)
                draw()
                return true
            }
        } catch (ex: Exception) {
            Platform.runLater { Dialogue.showException("Error", ex) }
        }
        return false
    }

    private fun init(cache: Cache) {
        if (!XTEAManager.load(xteaPath.toFile())) {
            return
        }

        TypeListManager.initialize(cache)
        Textures.initialize(cache)
        Sprites.initialize(cache)

        var lowestX: Region? = null
        var lowestY: Region? = null
        var highestX: Region? = null
        var highestY: Region? = null

        for (i in 0 until MAX_REGION) {
            val region = Region(i)

            val map = cache.getFileId(5, region.terrainIdentifier)
            val loc = cache.getFileId(5, region.locationsIdentifier)

            val progress = (i + 1).toDouble() / MAX_REGION * 100

            updateMessage(String.format("Initializing... %.2f%s", progress, "%"))
            updateProgress((i + 1).toDouble(), MAX_REGION.toDouble())

            if (map == -1 && loc == -1) {
                continue
            }

            if (map != -1) {
                region.loadTerrain(cache.read(5, map).data)
            }

            if (loc != -1) {
                val buffer = cache.store.read(5, loc)
                try {
                    region.loadLocations(Container.decode(buffer, XTEAManager.lookup(i)).data)
                } catch (e: Exception) {
                    if (buffer.limit() != 32) {
                        flags.add(i)
                    }
                }

            }

            regions.add(region)

            if (lowestX == null || region.baseX < lowestX.baseX) {
                lowestX = region
            }

            if (highestX == null || region.baseX > highestX.baseX) {
                highestX = region
            }

            if (lowestY == null || region.baseY < lowestY.baseY) {
                lowestY = region
            }

            if (highestY == null || region.baseY > highestY.baseY) {
                highestY = region
            }

        }

        val mapscene = Sprites.getSprite("mapscene")

        for (i in 0 until mapscene.size()) {
            mapIcons.put(i, mapscene.getFrame(i).getScaledInstance(4, 5, 0))
        }

        if (lowestX != null) {
            MapImageTask.lowestX = lowestX
        }

        if (lowestY != null) {
            MapImageTask.lowestY = lowestY
        }

        if (highestX != null) {
            MapImageTask.highestX = highestX
        }

        if (highestY != null) {
            MapImageTask.highestY = highestY
        }

    }

    private fun draw() {
        val minX = lowestX.baseX
        val minY = lowestY.baseY

        val maxX = highestX.baseX + 64
        val maxY = highestY.baseY + 64

        var dimX = maxX - minX
        var dimY = maxY - minY

        val boundX = dimX - 1
        val boundY = dimY - 1

        dimX *= MAP_SCALE
        dimY *= MAP_SCALE

        val baseImage = BufferedImage(dimX, dimY, BufferedImage.TYPE_INT_RGB)
        val fullImage = BufferedImage(dimX, dimY, BufferedImage.TYPE_INT_RGB)

        val graphics = fullImage.createGraphics()

        //Draw Underlay Map - Pass 1
        for (i in 0 until regions.size) {
            val region = regions[i]

            val baseX = region.baseX
            val baseY = region.baseY
            val drawBaseX = baseX - lowestX.baseX
            val drawBaseY = highestY.baseY - baseY

            for (x in 0..63) {
                val drawX = drawBaseX + x

                for (y in 0..63) {
                    val drawY = drawBaseY + (63 - y)

                    val overlayId = region.getOverlayId(0, x, y) - 1
                    val underlayId = region.getUnderlayId(0, x, y) - 1
                    var rgb = 0

                    if (overlayId > -1) {
                        val overlay = TypeListManager.lookupOver(overlayId)
                        if (!overlay.isHideUnderlay && underlayId > -1) {
                            val underlay = TypeListManager.lookupUnder(underlayId)
                            rgb = underlay.rgb
                        } else {
                            rgb = Color.CYAN.rgb
                        }
                    } else if (underlayId > -1) {
                        val underlay = TypeListManager.lookupUnder(underlayId)
                        rgb = underlay.rgb
                    } else {
                        rgb = Color.CYAN.rgb
                    }

                    drawMapSquare(baseImage, drawX, drawY, rgb)
                }
            }

            val progress = (i + 1).toDouble() / regions.size * 100

            updateMessage(String.format("Drawing [1/6] %.2f%s", progress, "%"))
            updateProgress((i + 1).toDouble(), regions.size.toDouble())
        }

        //Blend Underlay Map - Pass 2

        for (i in 0 until regions.size) {
            val region = regions[i]

            val baseX = region.baseX
            val baseY = region.baseY
            val drawBaseX = baseX - lowestX.baseX
            val drawBaseY = highestY.baseY - baseY

            for (x in 0..63) {
                val drawX = drawBaseX + x

                for (y in 0..63) {
                    val drawY = drawBaseY + (63 - y)

                    var c = getMapSquare(baseImage, drawX, drawY)

                    if (c == Color.CYAN)
                        continue

                    var tRed = 0
                    var tGreen = 0
                    var tBlue = 0
                    var count = 0

                    val maxDY = Math.min(boundY, drawY + 3)
                    val maxDX = Math.min(boundX, drawX + 3)
                    val minDY = Math.max(0, drawY - 3)
                    val minDX = Math.max(0, drawX - 3)

                    for (dy in minDY until maxDY) {
                        for (dx in minDX until maxDX) {
                            c = getMapSquare(baseImage, dx, dy)

                            if (c == Color.CYAN)
                                continue

                            tRed += c.red
                            tGreen += c.green
                            tBlue += c.blue
                            count++
                        }
                    }

                    if (count > 0) {
                        c = Color(tRed / count, tGreen / count, tBlue / count)
                        drawMapSquare(fullImage, drawX, drawY, c.rgb)
                    }
                }
            }

            val progress = (i + 1).toDouble() / regions.size * 100

            updateMessage(String.format("Drawing [2/6] %.2f%s", progress, "%"))
            updateProgress((i + 1).toDouble(), regions.size.toDouble())

        }

        //Draw Overlay Map - Pass 3
        for (i in 0 until regions.size) {
            val region = regions[i]

            val baseX = region.baseX
            val baseY = region.baseY
            val drawBaseX = baseX - lowestX.baseX
            val drawBaseY = highestY.baseY - baseY

            for (x in 0..63) {
                val drawX = drawBaseX + x

                for (y in 0..63) {
                    val drawY = drawBaseY + (63 - y)

                    val overlayId = region.getOverlayId(0, x, y) - 1
                    var rgb = -1

                    if (overlayId > -1) {
                        val overlay = TypeListManager.lookupOver(overlayId)
                        if (overlay.isHideUnderlay) {
                            rgb = overlay.rgb
                        }


                        if (overlay.secondaryRgb > -1) {
                            rgb = overlay.secondaryRgb
                        }


                        if (overlay.texture > -1) {
                            rgb = Textures.getColors(overlay.texture)
                        }

                    }

                    if (rgb > -1)
                        drawMapSquare(fullImage, drawX, drawY, rgb)
                }
            }

            val progress = (i + 1).toDouble() / regions.size * 100

            updateMessage(String.format("Drawing [3/6] %.2f%s", progress, "%"))
            updateProgress((i + 1).toDouble(), regions.size.toDouble())

        }

        //Draw Locations Map - Pass 4
        for (i in 0 until regions.size) {
            val region = regions[i]

            val baseX = region.baseX
            val baseY = region.baseY
            val drawBaseX = baseX - lowestX.baseX
            val drawBaseY = highestY.baseY - baseY

            for (location in region.locations) {
                if (location.position.height != 0) {
                    //	continue;
                }

                val objType = TypeListManager.lookupObject(location.id)

                val localX = location.position.x - region.baseX
                val localY = location.position.y - region.baseY

                val drawX = drawBaseX + localX
                val drawY = drawBaseY + (63 - localY)

                if (objType.mapscene != -1) {
                    val spriteImage = mapIcons[objType.mapscene]
                    graphics.drawImage(spriteImage, drawX * MAP_SCALE, drawY * MAP_SCALE, null)
                }
            }

            val progress = (i + 1).toDouble() / regions.size * 100

            updateMessage(String.format("Drawing [4/6] %.2f%s", progress, "%"))
            updateProgress((i + 1).toDouble(), regions.size.toDouble())
        }

        //Draw Icons Map - Pass 5
        for (i in 0 until regions.size) {
            val region = regions[i]

            val baseX = region.baseX
            val baseY = region.baseY
            val drawBaseX = baseX - lowestX.baseX
            val drawBaseY = highestY.baseY - baseY

            for (location in region.locations) {
                if (location.position.height != 0) {
                    //	continue;
                }

                val objType = TypeListManager.lookupObject(location.id)

                val localX = location.position.x - region.baseX
                val localY = location.position.y - region.baseY

                val drawX = drawBaseX + localX
                val drawY = drawBaseY + (63 - localY)

                if (objType.mapIcon != -1) {
                    val areaType = TypeListManager.lookupArea(objType.mapIcon)
                    val spriteImage = Sprites.getSprite(areaType.spriteId).getFrame(0)
                    graphics.drawImage(spriteImage, (drawX - 1) * MAP_SCALE, (drawY - 1) * MAP_SCALE, null)
                }
            }

            val progress = (i + 1).toDouble() / regions.size * 100

            updateMessage(String.format("Drawing [5/6] %.2f%s", progress, "%"))
            updateProgress((i + 1).toDouble(), regions.size.toDouble())

        }


        //Label/Outline/Fill regions - Pass 6
        for (i in 0 until regions.size) {
            val region = regions[i]

            val baseX = region.baseX
            val baseY = region.baseY
            val drawBaseX = baseX - lowestX.baseX
            val drawBaseY = highestY.baseY - baseY

            if (LABEL) {
                graphics.color = Color.RED
                graphics.drawString(region.regionID.toString(), drawBaseX * MAP_SCALE, drawBaseY * MAP_SCALE + graphics.fontMetrics.height)
            }

            if (OUTLINE) {
                graphics.color = Color.RED
                graphics.drawRect(drawBaseX * MAP_SCALE, drawBaseY * MAP_SCALE, 64 * MAP_SCALE, 64 * MAP_SCALE)
            }

            if (FILL) {
                if (flags.contains(region.regionID)) {
                    graphics.color = Color(255, 0, 0, 80)
                    graphics.fillRect(drawBaseX * MAP_SCALE, drawBaseY * MAP_SCALE, 64 * MAP_SCALE, 64 * MAP_SCALE)
                }
            }

            val progress = (i + 1).toDouble() / regions.size * 100

            updateMessage(String.format("Drawing [6/6] %.2f%s", progress, "%"))
            updateProgress((i + 1).toDouble(), regions.size.toDouble())

        }

        updateMessage("Writing full_image.png file")

        graphics.dispose()

        ImageIO.write(fullImage, "png", output.resolve("full_image.png").toFile())

    }

    private fun drawMapSquare(image:BufferedImage, x: Int, y: Int, rgb:Int) {
        val xScale = x * MAP_SCALE
        val yScale = y * MAP_SCALE

        for (dx in 0 until MAP_SCALE) {
            for (dy in 0 until MAP_SCALE) {
                image.setRGB(xScale + dx, yScale + dy, rgb)
            }
        }
    }

    private fun getMapSquare(image:BufferedImage, x:Int, y:Int) : Color {
        return Color(image.getRGB(x * MAP_SCALE, y * MAP_SCALE))
    }

    companion object {
        lateinit var lowestX: Region
        lateinit var lowestY: Region
        lateinit var highestX: Region
        lateinit var highestY: Region

        private val regions = ArrayList<Region>()
        private val flags = ArrayList<Int>()

        private val mapIcons = HashMap<Int, Image>()

        private val MAX_REGION = 32768
        private val MAP_SCALE = 2

        private val LABEL = true
        private val OUTLINE = true
        private val FILL = true
    }

}