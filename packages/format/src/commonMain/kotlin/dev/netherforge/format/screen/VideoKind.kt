package dev.netherforge.format.screen

import dev.netherforge.format.game.GameData
import dev.netherforge.format.project.DocumentResourceKind
import dev.netherforge.format.project.Layout
import dev.netherforge.format.project.ResourceContext

/** `videos/<id>/video.json`: a video screens play, converted by the editor into map colours and a sound. */
object VideoKind : DocumentResourceKind<VideoFile, VideoFile>(
    "video",
    "videos",
    Layout.Folder(VideoFile.FILE_NAME),
    VideoFile.serializer(),
    VideoFile.SCHEMA
) {
    override fun canonical(value: VideoFile) = value.copy(schema = schemaRef)

    override fun validate(value: VideoFile, ctx: ResourceContext) = VideoValidator.validate(value, ctx.sink, ctx.files)

    override fun compile(id: String, value: VideoFile, ctx: ResourceContext) = value

    /** Four maps across, three down: put the video beside it as `source.mp4` and convert it. */
    override fun template(id: String, game: GameData?) = mapOf(pathOf(id) to write(VideoFile(source = "source.mp4", width = 4, height = 3)))
}
