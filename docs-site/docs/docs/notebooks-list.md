The Notebooks List displays a listing of all the notebooks in your current workspace. 

![Notebooks List](images/notebooks-list.png){: .centered-image }

Notebooks are displayed in a directory structure. You can sort the list by name or modified time in ascending or 
descending order by clicking the respective column header. You can change the column width by clicking and dragging on 
the divider in the header bar between `Name` and `Modified`. 

Clicking on a notebook opens it, and clicking on a directory expands or collapses it. 

!!!tip
    The current workspace can be set in the [Storage Configuration](server-configuration.md#storage).

### Creating a new notebook

You can create a new notebook by clicking on the green plus sign
![green plus sign](images/green-plus.png){: .inline-image} in the corner.

This will bring up the Create Notebook Dialog, where you can enter the notebook name. If a notebook with that name 
already exists, a number will be appended at the end of the filename. 

!!!tip
    Any slashes in the notebook name will place your new notebook in a directory (creating them if they don't already 
    exist).

    If you'd like to quickly create a new notebook inside of an existing directory, right-click on any directory and select `New Notebook`. 

    Additionally, Polynote will add an extension if you don't specify one. 
    For example, typing in `foo/bar/My Notebook` will create a `My Notebook.ipynb` file and put it into a directory 
    named `bar`, within a directory named `foo`. 


### Importing a notebook 

Importing a notebook into Polynote is as simple as dragging and dropping the file onto the Notebooks List!

If you hover over and then drop your file into a sub-directory, your file will be imported into that sub-directory. 

![Dragging and Dropping a Notebook](images/drag-drop-notebook.gif)

!!!info "Supported import formats"
    Polynote supports importing your existing Jupyter `.ipynb` files and Zeppelin `.json` files. Zeppelin files will be 
    automatically converted into `.ipynb` files upon import. 

    Note that Polynote doesn't do any translation of the notebook contents, so any platform-specific features, such as 
    Jupyter "magics" (like Toree's `%AddJar`) or Zeppelin's `z.load` syntax will need to be manually converted. 

### Rename, Copy, Delete

You can Rename, Copy, and Delete notebooks using the Context Menu, which you can bring up by right-clicking on a 
notebook. 

Here's a demonstration of Renaming a notebook using the Context Menu. The Copy and Delete functions work rather 
similarly.

![Renaming notebook using Context Menu](images/context-menu-rename.gif)

