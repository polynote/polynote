# UNRELEASED 

* 

# 0.2.7 (October 23, 2019)

* Cross build for Scala 2.12
* Support for LaTeX MIME type output
* Fix some race conditions in Scala compiler
* Fix issue with nondeterministic queueing order
* Fix bug when notebook folder doesn't exist (Polynote now creates a notebook directory for you)
* Fix an issue where failed dependency downloads could cause notebook to be unresponsive until a restart


# 0.2.6 (October 18, 2019)

* Fix regression causing missing ExecutionInfo
* Fix regression causing the python interpreter shutdown to crash the kernel
* Fix regression causing symbol table to be stale after page reload
* Fix regression causing inserted cells not to be focused
* Remove spellcheck on code cells that would sometimes come up
* Always add a StringRepr. 
* Some minor cleanup of UI events
* Update fonts to address a bug in Firefox (https://bugzilla.mozilla.org/show_bug.cgi?id=1589156)
* Add some initial docs (more to come!)
* Update the logo (slightly)

# 0.2.5 (October 14, 2019)

* Subtasks!
  * Now related tasks are properly grouped together as parent and child tasks for less UI clutter
* Matplotlib backend
  * A proper Polynote backend for Matplotlib! Only supports regular old plotting, no interactive or animation support (for now)
* Finally added a license :) 
* Fix some bugs that were slowing down some Spark jobs. 
* Fix some bugs with Python-Scala interop
* Fix some more data encoding bugs

# 0.2.4 (October 10, 2019)

* Fix some bugs with data encoding
* Surface some error messages better in the UI
* Improve completions
* Add rudimentary auto-importing, kinda

# 0.2.3 (October 8, 2019)

* Better output display for some types
* Two new quick link buttons to go straight to the plot and data inspectors
* Fix race condition leading to the 'double typing issue'
* Fix some data encoding bugs
* Improvements and fixes to plot editor
* Better nullability handling across the board. 
* Fixes for some remote kernel sync issues
* Various bugfixes

# 0.2.2 (October 3, 2019)

* Fix bug setting spark output path
* Fix bug preventing download of dependencies
* Fix more completion bugs
* Fix more classloader bugs
* Fix more remote kernel bugs

# 0.2.1 (October 1, 2019)

* Fix cell queuing order bug
* add map types to schema view
* fix spark executors ui error
* fix remote kernel crash
* fix some bugs in Configuration UI
* fix case where some completions weren't working

# 0.2.0 (September 27, 2019)

* Significant rewrite of most backend code, switching over to ZIO! 
* Frontend ported to Typescript!

# 0.1.24 (September 9, 2019)

* Fix bug preventing jar dependencies with `+` in their name from working
* Fix broken restart of PySpark kernels. 
* minor UI fixes

# 0.1.23 (August 16, 2019)

* Support for being served under HTTPS

# 0.1.22 (August 14, 2019)

* Fix for `Shift+Enter` creating a newline rather than executing the cell
* Show tasks in Welcome screen
* Focus previous cell when last cell is deleted
* Remove formatting when converting text cells to code cells (e.g., if you copy/pasted some code and it has colors and such)
* New [About modal](https://user-images.githubusercontent.com/5430417/62907942-6dcdf100-bd2a-11e9-9065-acfcaf281c21.gif): 
    * Replaced barely used View Settings ugliness with fancy new modal with a bunch of info
    * `About` section has server version info
    * `Preferences` section has are for setting preferences and viewing/clearing storage (can be accessed directly by clicking the `gear` button.
        * The vim mode setting has been moved here
    * `Hotkeys` section has all available hotkeys (can be accessed directly by clicking the `?` button)
    * `Kernels` section shows all notebooks currently open by the server, with the ability to start/stop their kernel. 

# 0.1.21 (August 7, 2019)

* Fix hidden output off-by-one error
* Stability improvements for remote kernels
* Fix error causing failure to display inspector for certain types of Dataframes
* Fix error causing compilation failure when users define variable names that look like scala's generated synthetic members
  (e.g., a variable starting with `eq_`)
* Add version to app name
* minor refactoring to remove some `unsafeRun*`s. 

# 0.1.20 (August 1, 2019)

* Some minor UI improvements like
  * Collapse config when save button is pressed
  * Make it more obvious that output has been hidden
  * Fix some cases where the vim statusline disappeared
  * New Repr for Maps, show type in the data table, add Reprs for SparkSession and Runtime. 
  * Reload the UI upon reconnecting if it detects versions are out of whack. 
  * Fix cell anchor link regression
  * Make step execution favicon bubble green :) 
* Only create venv when python dependencies have been provided
* Overhaul python stack traces - get the actual error from py4j (e.g., pyspark) exceptions, clean up regular python stack traces. 
* Improve completions in the middle of a line by truncating it when sending to the presentation compiler
* Fix a bug preventing classes extending something imported in the same cell from working properly
* Display python docstrings and types (if possible) in Parameter hints
* Force spark session shutdown to run on the right thread

# 0.1.19 (July 23, 2019)

* Load the default.yml config even if config.yml itself is empty (normal case upon first installation)
* Add number of queued cells to favicon
* Disable running / editing cells upon disconnect. Attempt to reconnect when window is focused. 
* bust browser cache on release (hopefully you won't need to force-reload after upgrading any more!)
* Fix cyclic reference error
* Fix issue preventing vega interpreter from working properly if a string-typed variable was defined earlier
* Create a virtual environment every time. 
* Lazy vals no longer cause compile errors (they still have some problems though as detailed [here](https://github.com/polynote/polynote/issues/374))
* Fix compile error preventing definition of functions with default values. 

# 0.1.18 (July 17, 2019)

* Python Dependency support!
  * You can now specify Python dependencies (there's a little drop-down you can choose from in the Configuration panel). 
  * When you specify these dependencies, Polynote will create a notebook-specific virtual environment and install those dependencies inside it.
  * The virtual environment is configured to delegate to the system python environment so packages that already exist won't be installed again. It's persisted as well so it won't be recreated every time you restart polynote. 
* Support for base `default.yml` to be provided in distributions - no more clobbering people's configs!
* Fix for broken VIM mode 
* Improvements to data display:
  * Add chrome-inspector-style display of structures and arrays
  * Add Schema tab to inspector UI for table data
  * Some refactoring/removal of duplicate stuff
  * Add encoding of array fields to spark table data
* Create notebooks directory if it doesn't exist

# 0.1.17 (July 16, 2019)

* Move cell controls to top of cell, horizontally
  * Each cell has lang selector
  * Buttons to show/hide code and output
* Add new Vega spec interpreter for generating plots
* Add new inspection UI
  * Inspection comes up in a modal
  * Remove value column from symbol table
* Upgraded plot editor
  * Can save plot to a Vega spec cell
  * Better UI, can edit size and titles, functioning scatter plot
* Editor size adjusts after code folding

# 0.1.16 (July 3, 2019)

* Remove annoying jep uninstall message [#328]
* Overhaul Scala compilation for better serializability and stability. [#330]
  * Lift user-defined classes to package namespace (no more inner classes!) 
  * Only import necessary values from previous cells (rather than everything in that cell). Use proxy values rather 
    than imports to avoid closing over the entire cell. 
* Fix regression causing wide output to stretch cell too far [#333]
* Improvements to completions [#335]

[#328]: https://github.com/polynote/polynote/pull/328
[#330]: https://github.com/polynote/polynote/pull/330
[#333]: https://github.com/polynote/polynote/issues/333
[#335]: https://github.com/polynote/polynote/pull/335

# 0.1.15 (May 20, 2019)

* Install jep globally rather than locally [#324]

[#324]: https://github.com/polynote/polynote/pull/324

# 0.1.14 (May 20, 2019)

* Fix lingering positioning bugs (completions should work better now; no `OuterScopes` stuff)
* Fix spark serializer issue
* Minor style fixes

# 0.1.13 (May 16, 2019)

* Add support for simple references to `globals()` [#19]
* Fix Python scoping issue causing NameErrors for imports references inside inner scopes (e.g., in a function) [#315]
* Add `@transient` annotation to `polynote.runtime.Runtime.externalValues`, preventing Spark from serializing it. [#316]

[#19]: https://github.com/polynote/polynote/issues/19
[#315]: https://github.com/polynote/polynote/pull/315
[#316]: https://github.com/polynote/polynote/issues/316

# 0.1.12 (May 13, 2019)

* Fix Python output folding issue [#295]
* Duplicate notebook names now handled gracefully, we'll just increment the filename until there's no conflict [#296]
* Python `SyntaxError`s raised by parsing cell contents now raised as `CompileErrors` rather than Runtime Errors. [#301]
* Fix regression which broke implicits [#313]
* Improve performance of UI pane resizing, other minor UI improvements

[#295]: https://github.com/polynote/polynote/issues/295
[#296]: https://github.com/polynote/polynote/issues/296
[#301]: https://github.com/polynote/polynote/issues/301
[#313]: https://github.com/polynote/polynote/pull/313

# 0.1.11 (May 10, 2019)
* Fix positioning regression introduced in 0.1.10
* Only close over cells that are referenced in the current cell

# 0.1.10 (May 9, 2019)

* Can now bust cache of URL dependencies (e.g., `file:///`, `s3://`, etc) by adding URL parameter `?nocache` to the end of the path [#292]
* Support `s3n` and `s3a` URLs as well (just a config change on our end). 
* Fix bug in how we were assigning Python cell expressions to `Out` [#291]
* Fix Spark serialization issues [#300]

[#292]: https://github.com/polynote/polynote/issues/292
[#291]: https://github.com/polynote/polynote/issues/291
[#300]: https://github.com/polynote/polynote/pull/300

# 0.1.9 (May 3, 2019)

* Fix error when printing empty lines in python
* Fix data streaming on remote kernels
* Add table operations for sequences of case classes (supports plotting)
* Clear error squigglies when cell is run
* Save cell language into metadata field of ipynb
* Improved support for terminal output

# 0.1.8 (April 29, 2019)

* Fix some bugs that caused cell execution to hang
* Fix regression which caused repr display not to show up
* Fix some scala interpreter bugs, including one that prevented case classes (and other things) defined in a class from 
  being visible to other cells
* Better numpy support, fix for `Message class can only inherit from Message` error

# 0.1.7 (April 25, 2019)

* Fix an embarrassingly bad bug in [#241]

# 0.1.6 (April 25, 2019)

* Update coursier, giving an order-of-magnitude performance boost to dependency resolution (especially for deep dependency trees)
* Hotkey revamp [#241]
  * Share implementation across monaco and text cells
  * Added new hotkeys: Delete cell (ctrl-option-D), add cell above (ctrl-option-A), add cell below (ctrl-option-B)
  * Up and Down arrows now transition to neighboring cells if cursor is at start/end of cell text
* Display cell execution time while it is running [#253]
* Fix RuntimeError when using a numpy array. 

[#241]: https://github.com/polynote/polynote/issues/241
[#253]: https://github.com/polynote/polynote/issues/253

# 0.1.5 (April 19, 2019)

* Support for importing Zeppelin notebooks [#185]
  * Just drag and drop your `note.json` onto the tree view. 
  * Notebook creation and URL import are now two separate buttons. 
* Can now click between cells to create a new cell [#235]
* New clear output button - deletes all results/outputs of a notebook from UI and underlying file [#237]
* Fix scrolling behavior for selected cell [#240]

[#185]: https://github.com/polynote/polynote/issues/185
[#235]: https://github.com/polynote/polynote/issues/235
[#237]: https://github.com/polynote/polynote/issues/237
[#240]: https://github.com/polynote/polynote/issues/240

# 0.1.4 (April 16, 2019) 

* Style fixes and tweaks [#219], [#230]
* Fix delegation of failed classloadings [#246]

[#219]: https://github.com/polynote/polynote/pull/219
[#230]: https://github.com/polynote/polynote/pull/230
[#246]: https://github.com/polynote/polynote/pull/246

# 0.1.3 (April 12, 2019)

* Import and Export of Notebooks [#215]
  * New download button in Notebook toolbar downloads the ipynb representation of the notebook
  * Can import ipynb files by drag and drop onto the notebooks sidebar UI
  * Additionaly, can import notebooks directly from another Polynote instance when creating a notebook. 
    Just specify a URL instead of a name for the new notebook. 
    
* UI cleanup [#224]
  * drag borders always visible
  * some fixes for notebook panel view
* Vim mode no longer swallows Shift+Enter [#226]
* Fix bug preventing selection of leftmost tab when notebook panel was collapsed [#228]
* Fix bug causing output doubling [#227]
* Fix bug causing run script to fail when certain values were present in the config file [#232]
* Logging and Error visibility improvements [#218]
  * Kernel Error task message now includes stack trace
  * Log kernel errors to Polynote output instead of just UI. 
  * Run script by default tees logs to file to help debugging later

[#215]: https://github.com/polynote/polynote/issues/215
[#218]: https://github.com/polynote/polynote/issues/218
[#224]: https://github.com/polynote/polynote/pull/224
[#226]: https://github.com/polynote/polynote/pull/226
[#227]: https://github.com/polynote/polynote/issues/227
[#228]: https://github.com/polynote/polynote/issues/228
[#232]: https://github.com/polynote/polynote/pull/232

# 0.1.2 (April 5, 2019)

* Run scripts included in HTML Output [#205]
* Add UI support for warnings
* Warn (rather than error) if eta-expansion fails [#216]
* Collapsible sidebars [#11]
* Cells now show execution progress of top-level statements (scala cells only) [#221]
* Add VIM mode [#220]
* Additional bug fixes and UI tweaks ([#212], [#222])

[#11]:  https://github.com/polynote/polynote/issues/205
[#205]: https://github.com/polynote/polynote/issues/205
[#212]: https://github.com/polynote/polynote/issues/212
[#216]: https://github.com/polynote/polynote/pull/216
[#220]: https://github.com/polynote/polynote/issues/220
[#221]: https://github.com/polynote/polynote/pull/221
[#222]: https://github.com/polynote/polynote/pull/222




# 0.1.1 (April 2, 2019)

* Initial release of Polynote! :) 
