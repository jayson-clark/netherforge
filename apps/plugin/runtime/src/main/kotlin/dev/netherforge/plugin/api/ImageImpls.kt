package dev.netherforge.plugin.api

import dev.netherforge.format.image.Pixels
import dev.netherforge.plugin.async.WorkFailed
import dev.netherforge.plugin.binding.Caller
import dev.netherforge.plugin.binding.HttpRequestOptions
import dev.netherforge.plugin.binding.ImageApi
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.NfImagesApi
import dev.netherforge.plugin.binding.StringListOrString
import dev.netherforge.plugin.image.Images
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.session.ProjectSession
import java.util.Base64
import java.util.concurrent.CompletionStage

/** `nf.images`: pictures made at runtime, kept in `session.images` while Lua holds their handles. */
internal class NfImagesImpl(private val session: ProjectSession, private val http: NfHttpImpl) : NfImagesApi {
    override fun create(caller: Caller, width: Long, height: Long, pixels: StringListOrString): LuaHandle.Image {
        size(width, height)
        val count = (width * height).toInt()
        val argb = when (pixels) {
            is StringListOrString.StringList -> {
                if (pixels.value.size != count) {
                    throw LuaApiException("bad argument 'pixels' ($width by $height is $count pixels, got ${pixels.value.size})")
                }
                IntArray(count) { i ->
                    Images.color(pixels.value[i])
                        ?: throw LuaApiException("bad argument 'pixels[${i + 1}]' (#RRGGBB or #AARRGGBB, got \"${pixels.value[i]}\")")
                }
            }
            is StringListOrString.String -> {
                val bytes = base64(pixels.value) ?: throw LuaApiException("bad argument 'pixels' (base64 bytes, or a list of colours)")
                when (bytes.size) {
                    count * 3 -> IntArray(count) { i ->
                        val o = i * 3
                        (0xFF shl 24) or (byte(bytes, o) shl 16) or (byte(bytes, o + 1) shl 8) or byte(bytes, o + 2)
                    }
                    count * 4 -> IntArray(count) { i ->
                        val o = i * 4
                        (byte(bytes, o + 3) shl 24) or (byte(bytes, o) shl 16) or (byte(bytes, o + 1) shl 8) or byte(bytes, o + 2)
                    }
                    else -> throw LuaApiException(
                        "bad argument 'pixels' ($width by $height is ${count * 3} bytes as RGB or ${count * 4} as RGBA, got ${bytes.size})"
                    )
                }
            }
        }
        return handle(Pixels(width.toInt(), height.toInt(), argb))
    }

    override fun fill(caller: Caller, width: Long, height: Long, color: String): LuaHandle.Image {
        size(width, height)
        val pixel = Images.color(color) ?: throw LuaApiException("bad argument 'color' (#RRGGBB or #AARRGGBB, got \"$color\")")
        return handle(Pixels(width.toInt(), height.toInt(), IntArray((width * height).toInt()) { pixel }))
    }

    override fun decode(caller: Caller, data: String): LuaHandle.Image? {
        val bytes = base64(data) ?: return null
        return Images.decode(bytes)?.let(::handle)
    }

    override fun download(caller: Caller, options: HttpRequestOptions): CompletionStage<LuaHandle.Image> {
        val images = session.images
        return http.fetch(caller, options, "nf.images.download") { reply ->
            if (reply.status !in 200..299) throw WorkFailed("the server answered ${reply.status}")
            val pixels = Images.decode(reply.bytes)
                ?: throw WorkFailed("what came back isn't an image (PNG, JPEG, GIF or BMP) of at most ${Images.MAX_SIDE} pixels either way")
            try {
                LuaHandle.Image(images.hold(pixels))
            } catch (e: Images.TooMany) {
                throw WorkFailed(e.message.orEmpty())
            }
        }
    }

    private fun handle(pixels: Pixels): LuaHandle.Image = try {
        LuaHandle.Image(session.images.hold(pixels))
    } catch (e: Images.TooMany) {
        throw LuaApiException(e.message.orEmpty())
    }

    private fun base64(text: String): ByteArray? = try {
        Base64.getDecoder().decode(text.trim())
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun byte(bytes: ByteArray, at: Int) = bytes[at].toInt() and 0xFF

    companion object {
        /** An error at the script's line unless [width] by [height] is an image's size. */
        fun size(width: Long, height: Long) {
            if (!Images.fits(width, height)) {
                throw LuaApiException("an image is 1 to ${Images.MAX_SIDE} pixels each way, not $width by $height")
            }
        }
    }
}

/** `Image`: the pictures `nf.images` made, read from `session.images`. Lua only holds a handle while the picture is there. */
internal class ImageImpl(private val session: ProjectSession) : ImageApi {
    private fun of(self: LuaHandle.Image): Pixels =
        session.images[self.number] ?: throw LuaApiException("this image is gone (the session that made it ended)")

    override fun width(self: LuaHandle.Image): Long = of(self).width.toLong()

    override fun height(self: LuaHandle.Image): Long = of(self).height.toLong()

    override fun pixel(self: LuaHandle.Image, x: Long, y: Long): String? {
        val image = of(self)
        if (x !in 0 until image.width || y !in 0 until image.height) return null
        return Images.colorName(image[x.toInt(), y.toInt()])
    }

    override fun resized(self: LuaHandle.Image, width: Long, height: Long): LuaHandle.Image {
        NfImagesImpl.size(width, height)
        return hold(of(self).resized(width.toInt(), height.toInt()))
    }

    override fun cropped(self: LuaHandle.Image, x: Long, y: Long, width: Long, height: Long): LuaHandle.Image? {
        val image = of(self)
        if (width < 1 || height < 1 || x < 0 || y < 0 || x + width > image.width || y + height > image.height) return null
        val w = width.toInt()
        val left = x.toInt()
        val top = y.toInt()
        return hold(Pixels(w, height.toInt(), IntArray(w * height.toInt()) { i -> image[left + i % w, top + i / w] }))
    }

    override fun encode(self: LuaHandle.Image): String = Base64.getEncoder().encodeToString(Images.encode(of(self)))

    private fun hold(pixels: Pixels): LuaHandle.Image = try {
        LuaHandle.Image(session.images.hold(pixels))
    } catch (e: Images.TooMany) {
        throw LuaApiException(e.message.orEmpty())
    }
}
