# UNRELEASED 

* 

# 0.1.3 (April 12, 2019)

* Import and Export of Notebooks https://github.com/polynote/polynote/issues/215
  * New download button in Notebook toolbar downloads the ipynb representation of the notebook
  * Can import ipynb files by drag and drop onto the notebooks sidebar UI
  * Additionaly, can import notebooks directly from another Polynote instance when creating a notebook. 
    Just specify a URL instead of a name for the new notebook. 
    
* UI cleanup https://github.com/polynote/polynote/pull/224
  * drag borders always visible
  * some fixes for notebook panel view
* Vim mode no longer swallows Shift+Enter https://github.com/polynote/polynote/pull/224
* Fix bug preventing selection of leftmost tab when notebook panel was collapsed https://github.com/polynote/polynote/issues/228
* Fix bug causing output doubling https://github.com/polynote/polynote/issues/227
* Fix bug causing run script to fail when certain values were present in the config file https://github.com/polynote/polynote/pull/232
* Logging and Error visibility improvements https://github.com/polynote/polynote/issues/218
  * Kernel Error task message now includes stack trace
  * Log kernel errors to Polynote output instead of just UI. 
  * Run script by default tees logs to file to help debugging later
  

# 0.1.2 (April 5, 2019)

* Run scripts included in HTML Output #205
* Add UI support for warnings
* Warn (rather than error) if eta-expansion fails #216
* Collapsible sidebars #11
* Cells now show execution progress of top-level statements (scala cells only) #221
* Add VIM mode #220
* Additional bug fixes and UI tweaks (#212, #222)

# 0.1.1 (April 2, 2019)

* Initial release of Polynote! :) 