**ENSIME: The Next Generation** is a full rewrite of the [ENhanced Scala Interaction Mode for Emacs](https://ensime.github.io).

ENSIME runs as a compiler plugin, extracting compiler settings in a build-tool agnostic manner.

The same plugin is invoked through its command line interface from your text editor. It reads the output of the plugin to understand the dependencies and uses the Scala compiler's internal APIs to answer semantic questions.

# Features

The feature set is constrained to allow the codebase to be lean and maintainable:

- dot completion
- infer type
- import / search for class
- jump to source

# Installation

## Server

If upgrading, always delete old versions of ENSIME first

```
rm -rf ~/.cache/ensime
```

Then install the latest version with

```
sbt +install
sbt lsp/install
```

from this directory and then install the extension for the text editor of your choice.

You may need to install additional builds for specific versions of the compiler for projects (and their project definitions) that are using an older version of the compiler, e.g.

```
sbt ++2.12.15! install
```

To get "jump to source" support for the Java standard library, make sure to install the `src.zip` into `JAVA_HOME/lib/src.zip` for all the JVMs that you are using.

## Text Editor

### Emacs

Install the `ensime-mode` when enabling `scala-mode` and add your keybindings, e.g.

```lisp
(use-package ensime-mode
  :ensure nil
  :load-path "/path/to/ensime-tng/lisp"
  :commands ensime-mode
  :bind
  (:map ensime-mode-map
        ("M-." . ensime-jump-to-definition)
        ("C-c C-i t" . ensime-type-at-point)
        ("C-c C-i s" . ensime-symbol-at-point)
        ("C-c C-r i" . ensime-import-symbol-at-point)))

(add-hook 'scala-mode-hook #'ensime-mode)
```

### VSCode

Go to the Extension manager and "Install from VSIX", choosing the ENSIME `.vsix` file.

You may wish to bind the custom command "Import Symbol" to a hotkey.

VSIX files are made available at https://ensime.github.io/ but if you want to build from source then use:

```
cd lsp/vscode
npm install
npm run package
```

### NeoVim

Copy the file from the `nvim` subdirectory to wherever extensions are supposed to go in your home directory.

### Sublime

TODO https://github.com/sublimelsp/LSP

## Build Tools

### SBT

When building and installing the compiler plugin, we took the liberty of installing an sbt plugin for you. Everything will be automatic.

### Mill

ENSIME must be installed for every Mill project. Copy the `mill/Ensime.sc` file into the `plugins` folder of your project and follow the instructions in the comments of that file.

Vote or comment on https://github.com/com-lihaoyi/mill/discussions/2075 to get this added to Mill by default.

# Known Issues

Only tested on GNU/Linux, and to a lesser extent MacOS. Windows almost certainly needs additional support.

ScalaJS and Scala Native do not have any special handling and your mileage may vary.

The Scala 3 compiler regresses many behaviours and features may be degraded, e.g. completions for extension methods might include erroneous extra parameters.

# Contributing

ENSIME is an invite-only project for hobbyists who write tooling to make their lives a little bit more joyful.

If you have access to the repository, you are requested not to share the commit history publicly.

Public snapshot releases may appear on https://ensime.github.io/, at the discretion of the authors, which may be shared freely.

# Design

## Editor Plugin

Responsible for prompting the user to install the compiler plugin and issuing queries.

## Compiler Plugin

The plugin runs as early as possible, creating the following output:

### Launcher

For every source file a file is written into the cache containing all the information that is needed to launch the interactive compiler.

[`ng`](https://github.com/facebook/nailgun) is compiled and used so that ENSIME runs as a background server with millisecond response times. This only requires `cc` to be available.

### Local Source Lookup

For every output directory a file is written into the cache containing the list of source files that produced the binary.

## Testing

The most important tests are end-to-end regression tests. Example projects are compiled, and a series of scripted user interactions are performed on them. The output for each action is persisted as a test assertion and variations from this baseline are failures.

## LSP

For editors that cannot invoke the launcher directly, an LSP wrapper is provided which will do this for them. It is written in Scala but could have been written in any language. Only a single LSP is needed to service all open projects since the launcher is managing all of that, however the LSP server does need to be stateful to keep track of the open list of files and their in-memory edits.

## Features

### Completion at Point

Requires the source file and location at point, plus (optionally) a list of all open scala files.

The location must follow a `.` since only member completions are supported.

Using the presentation compiler, find the type of the symbol preceeding the `.` and list:

- symbols (packages, methods, objects, types, etc) that live on that type
- extension methods that live on that type

returning their full scala signature, one per line.

Use of the presentation compiler follows the same time travel optimisation as ENSIME: The Original Series, in that the `target` directory is provided as a dependency to its own source. This way the presentation compiler only needs to compile source code that is being queried, not all the source files in the project. This carries the caveat that ENSIME works best when the project has been cleanly compiled recently.

This feature may be extended to work in additional positions (e.g. following keywords such as `new`, `extends`, `with`).

### Infer Type

Requires the source file and location.

Uses the presentation compiler to find the type of the symbol at point returning the fully qualified scala signature. Symbolic types having 2 type parameters are rendered infix in short form only.

### Search for Class

Requires a class name (simple name or fuzzy camel case) and an optional source file and location.

All class names that match the search are returned. Classfiles are parsed to filter out non-public and non-static classes.

If a source file and location is provided, it is used to filter entries to exclude those that are already visible at that location, which is ideal for the "import class at point" feature.

### Jump to Source

Requires the source file and location of the symbol to search (jump to source at point), or a java descriptor (jump to source of search result).

Requires that sources are downloaded manually.

Uses the presentation compiler to find the symbol at point (unless the symbol at point is the definition site, in which case we repeat for the type of the symbol at point. The symbol is then converted into a java descriptor, which allows us to look up all class files on the classpath that match the prefix of that symbol, then parse the classfile to find the source file name and line number.

Heuristics are then used to locate source directories and archives that are associated to the class directory or jar file, aided by reverse lookups produced by the compiler plugin.

All matching files from that source directory are returned, allowing the user to disambiguate identically named files. We may filter the source files based on a very simple root package name scan.
