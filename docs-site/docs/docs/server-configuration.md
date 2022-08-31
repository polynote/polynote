To change any of the default configuration, you'll need to copy the included [`config-template.yml`](https://github.com/polynote/polynote/blob/master/config-template.yml)
file to `config.yml`, and uncomment the sections you'd like to change. Check out the template itself for more information. 

Note that any changes will only take effect upon restarting your Polynote instance. 

### Templates 

![Notebook Templates](images/notebook-templates.png)

Templates allow you to clone an existing notebook from the UI. *Clone* means you will be able to copy the entire contents 
of the notebook - including its configuration, dependency lists, code cells (and their previous output), etc. 

For now, templates can only be absolute file paths available to be read by your system. To include this path, create a 
list under `notebook_templates` under `behavior` in your `config.yml` file. 

Once you've included your list, restart Polynote - you should see the options available when creating a notebook. 