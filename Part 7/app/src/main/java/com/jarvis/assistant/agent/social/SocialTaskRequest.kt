package com.jarvis.assistant.agent.social

data class SocialTaskRequest(
    val platform: Platform,
    val action: SocialAction,
    val mediaUri: String? = null,
    val caption: String? = null,
    val mode: TaskMode = TaskMode.PUBLISH,
    val targetAccount: String? = null
) {
    companion object {
        fun parse(args: Map<String, Any?>): Result<SocialTaskRequest> {
            val platformStr = args["platform"]?.toString()?.uppercase()?.trim()
                ?: return Result.failure(IllegalArgumentException("Missing required parameter 'platform'"))
            val platform = try {
                Platform.valueOf(platformStr)
            } catch (_: Exception) {
                return Result.failure(IllegalArgumentException("Unsupported platform '$platformStr'"))
            }

            val actionStr = args["action"]?.toString()?.uppercase()?.trim()
                ?: return Result.failure(IllegalArgumentException("Missing required parameter 'action'"))
            val action = try {
                SocialAction.valueOf(actionStr)
            } catch (_: Exception) {
                return Result.failure(IllegalArgumentException("Unsupported action '$actionStr'"))
            }

            val modeStr = args["mode"]?.toString()?.uppercase()?.trim() ?: "PUBLISH"
            val mode = try {
                TaskMode.valueOf(modeStr)
            } catch (_: Exception) {
                TaskMode.PUBLISH
            }

            val mediaUri = args["media_uri"]?.toString()?.trim()
            if (action in listOf(SocialAction.CREATE_POST, SocialAction.CREATE_REEL, SocialAction.CREATE_STORY)) {
                if (mediaUri.isNullOrBlank()) {
                    return Result.failure(IllegalArgumentException("Action $action requires 'media_uri'"))
                }
                if (!MediaResolver.isValidMediaUri(mediaUri)) {
                    return Result.failure(IllegalArgumentException("Media URI must be a content:// URI shared with the app, was: $mediaUri"))
                }
            }

            val caption = args["caption"]?.toString()?.trim()
            val targetAccount = args["target_account"]?.toString()?.trim()

            return Result.success(
                SocialTaskRequest(
                    platform = platform,
                    action = action,
                    mediaUri = mediaUri,
                    caption = caption,
                    mode = mode,
                    targetAccount = targetAccount
                )
            )
        }
    }
}
