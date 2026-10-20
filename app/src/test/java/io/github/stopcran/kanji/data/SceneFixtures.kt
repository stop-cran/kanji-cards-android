package io.github.stopcran.kanji.data

import java.util.Base64

internal fun sceneWebp(blue: Boolean = false): ByteArray = Base64.getDecoder().decode(
    if (blue) "UklGRhwAAABXRUJQVlA4TA8AAAAvA4AAAAcQ0f/+ByKi/wEA"
    else "UklGRhwAAABXRUJQVlA4TA8AAAAvA4AAAAcQ/Y/+ByKi/wEA",
)

internal fun sceneWebp(index: Int): ByteArray = Base64.getDecoder().decode(
    listOf(
        "UklGRhwAAABXRUJQVlA4TA8AAAAvA4AAAAcQ/Y/+ByKi/wEA",
        "UklGRhwAAABXRUJQVlA4TA8AAAAvA4AAAAcQ0f/+ByKi/wEA",
        "UklGRhwAAABXRUJQVlA4TA8AAAAvA4AAAAdQwIj+ByKi/wEA",
        "UklGRhwAAABXRUJQVlA4TBAAAAAvA4AAAAfQ//5H/wMR0f8A",
        "UklGRhwAAABXRUJQVlA4TBAAAAAvA4AAAAcQBSxg/wMR0f8A",
        "UklGRhwAAABXRUJQVlA4TBAAAAAvA4AAAAfQ/+h//wMR0f8A",
        "UklGRh4AAABXRUJQVlA4TBEAAAAvA4AAAAfQ//73v/+BiOh/AAA=",
        "UklGRhoAAABXRUJQVlA4TA4AAAAvA4AAAAcQEf0PRET/Aw==",
        "UklGRhwAAABXRUJQVlA4TBAAAAAvA4AAAAfQ0v5H/wMR0f8A",
        "UklGRh4AAABXRUJQVlA4TBEAAAAvA4AAAAdQ4P53uf+BiOh/AAA=",
    )[index],
)
