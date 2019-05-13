# UNRELEASED

* Fix Python output folding issue [#295]
* Duplicate notebook names now handled gracefully, we'll just increment the filename until there's no conflict [#296]
* Python `SyntaxError`s raised by parsing cell contents now raised as `CompileErrors` rather than Runtime Errors. [#301]

[#295]: https://github.com/polynote/polynote/issues/295
[#296]: https://github.com/polynote/polynote/issues/296
[#301]: https://github.com/polynote/polynote/issues/301

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
