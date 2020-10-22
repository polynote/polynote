package polynote.server.repository.db

import java.util.{Date, UUID}

/* NOTE: this class does NOT contain notebook contents, i.e. the Polynote JSON code */
case class NotebookEntity(notebookId: String,   /* this is a UUID for the notebook */
                          folderPath: String,   //  These two items represent a unique key
                          name: String,         //  ""
                          description: String,
                          version: Int,
                          // createdBy: String     // for createdBy, see userId
                          createdOn: Date,
                          updatedBy: String,
                          updatedOn: Date)

case class NotebookListItem(folderPath: String,
                            name: String)

/* Use this class if you want notebook content */
case class NotebookContent(notebookId: UUID,   /* this is a UUID for the notebook */
                           name: String,
                           description: String, // we need to carry this because copy uses it.
                           folderPath: String,
                           /* these are updated whenever the content changes */
                           content: String,
                           updatedOn: Date)
