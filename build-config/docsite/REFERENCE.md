# What the reference actually looks like

Measured from JetBrains' IntelliJ IDEA help, which is built with the same builder as this
site. Regenerate with `python3 build-config/docsite/reference.py --fetch`.

Corpus: 1,296 pages, 1,429,617 words, 65,365 sentences.

## Sentence length

| | Words |
| --- | --- |
| median | 9 |
| 75th percentile | 14 |
| 90th percentile | 21 |
| 99th percentile | 52 |

**Their median sentence is 9 words.** Half of everything they publish is shorter
than that. A limit of 32 words does not describe this documentation; it describes something
three times its length that happens not to be worse.

## Register, per 10,000 words

| Word | Rate | |
| --- | --- | --- |
| `you` | 117 | The reader is in almost every paragraph. |
| `your` | 46 | |
| `if you` | 20 | Conditions are put to the reader, not stated abstractly. |
| `click` / `select` / `press` | 147 | Written as what the reader does. |
| `we` | 2.9 | Rare, and always a recommendation. |
| `simply` | 0.05 | Seven times in 1 million words. |
| `just` | 1.3 | |

183 of 468 measurable page openings address the reader in their first
sentence.

## How they open a page

Verbatim first sentences, taken mechanically — the first 24 pages whose
title is short enough to sit beside them.

| Page | Its first sentence |
| --- | --- |
| New in IntelliJ IDEA 2026.2 | Learn about the changes in IntelliJ IDEA 2026.2. |
| Accessibility | IntelliJ IDEA lets you enable various accessibility features to accommodate your needs. You can |
| IntelliJ IDEA overview | IntelliJ IDEA is an Integrated Development Environment (IDE) for professional development |
| User interface | When you open a project in IntelliJ IDEA, the default user interface looks as follows: |
| Pro tips | This guide targets IntelliJ IDEA users who are already familiar with its basic features and would like to learn |
| Support and assistance | Procedure: Access online documentation from the IDE |
| Install IntelliJ IDEA | IntelliJ IDEA is a cross-platform IDE that provides a consistent experience on Windows, macOS, and Linux. |
| Register IntelliJ IDEA | You can evaluate IntelliJ IDEA Ultimate free of charge for up to 30 |
| Update IntelliJ IDEA | By default, IntelliJ IDEA is configured to check for updates automatically and notify you when a new version is available. |
| Uninstall IntelliJ IDEA | The proper way to remove IntelliJ IDEA depends on the method you used to install it. |
| Create your first Java application | In this tutorial, you will learn how to create, run, and package a simple Java application that prints |
| Learn IDE features | IntelliJ IDEA includes a built-in Features Trainer. This interactive training course on IDE basics can |
| Plugins | Plugins extend the core functionality of IntelliJ IDEA. |
| Work offline | A lot of features in IntelliJ IDEA |
| Migrate from Cursor | Cursor utilizes a blank canvas approach, giving users the flexibility to customize the environment according |
| Migrate from Windsurf | Windsurf is built around a highly customizable and minimal setup, allowing users to set up the environment to |
| IntelliJ IDEA for Education | IntelliJ IDEA can be used for learning and teaching programming. |
| IntelliJ IDEA tutorials | Here you can find IntelliJ IDEA tutorials designed to help you write quality code, |
| JetBrainsd service | The daemon (`jetbrainsd`) is a background service that coordinates JetBrains tools on your machine. |
| Configuring the IDE | IntelliJ IDEA allows you to configure the settings on several levels: the [module](configure-modules.html) |
| New UI | The new user interface (UI) is a new redesigned look of IntelliJ IDEA. It has been created to reduce visual complexity, provide easy |
| Arrange tool windows | By default, [tool windows](tool-windows.html) are attached to the edges of the main window. |
| Menus and toolbars | As you work with the IDE, you perform some actions more often than the others. |
| User interface themes | The interface theme defines the appearance of windows, dialogs, buttons, and all visual elements of the user interface. |

## What this means for this site

1. **Write to the reader.** `you` and `your` are the normal register, not a lapse. A sentence
   that avoids the reader by naming a mechanism instead — *the plugin is configured by* rather
   than *you configure the plugin by* — is longer, colder and harder to act on.
2. **Short sentences.** Aim at the median, not the limit. One clause, one fact.
3. **A picture early.** Their pages put an image within a screen of the opening, not at the
   end as evidence.
4. **Say what it lets the reader do**, then how. `IntelliJ IDEA lets you enable...` is the
   shape: subject, what it enables, then the steps.
