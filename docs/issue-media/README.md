# Issue media

Frames and collages committed so an issue can show the thing it is about.

`build/debug-screenshots` is gitignored and the gallery binds the LAN, so an image only the
build box can see proves nothing to a reader. When you file an issue about something visible,
copy the frame here, commit it, and **link** it:

```
cp build/debug-screenshots/<shot>.png docs/issue-media/issue<N>-<what>.png
git add docs/issue-media && git commit && git push origin example
```

```
https://github.com/wildware-uk/Udea/blob/example/docs/issue-media/<file>.png
```

**Link, never embed.** This repository is private, so GitHub's image proxy cannot fetch a raw
URL and an inline `![]()` renders broken for everyone. A blob link works, because the reader is
authenticated when they click it.

One line saying what the frame shows and what it proves. An issue about something with nothing
to see — a contract, a build rule, a decision — carries a transcript instead and says so.
