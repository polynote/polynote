package polynote.server.repository.fs

import java.io.{ByteArrayInputStream, FileNotFoundException, InputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Path, Paths}
import java.util.Date

import fs2.Chunk
import polynote.env.ops.Location
import polynote.kernel.BaseEnv
import zio.blocking.{Blocking, effectBlocking}
import zio.interop.catz._
import zio.{RIO, Task, ZIO}
import polynote.kernel.logging.Logging
import polynote.server.repository.db.Database.DbContext
import polynote.server.repository.db.{NotebookContent, NotebookListItem}
import polynote.server.repository.db.Postgres
import polynote.server.repository.fs.LocalFilesystem.FileChannelWALWriter

import scala.collection.JavaConverters._

/**
 * Implementation of NotebookFilesystem to provide database
 * CRUD operations that mimics a file system.
 *
 * Author: Clay Lambert, additions by Ken Hawley
 *  */
class DatabaseFilesystem(databaseContext: Postgres, maxDepth: Int = 4) extends NotebookFilesystem {

    var dbContext: DbContext = null
    var database: Database = null

    /**
     * retrieve the content of a notebook from the database.
     */
    override def readPathAsString(path: Path): RIO[BaseEnv, String] = {
        // synchronous, Quill, initialize the stream
        val nbInputStream = getNotebookContentStream(path.toFile.getPath)
        for {
            // private def readBytes(is: => InputStream): zio.RIO[kernel.BaseEnv, Chunk.Bytes]
            content <- readBytes(nbInputStream)
        }
        yield new String(content.toArray, StandardCharsets.UTF_8)
    }

    /**
     * Returns the notebook content from the database wrapped in a Byte oriented stream.
     * This makes a synchronous Quill call
     */
    private def getNotebookContentStream(path: String): ByteArrayInputStream = {
        val pathAndName = extractPathAndName(path)
        val content = database.getNotebookContent(pathAndName._2, pathAndName._1)
        new ByteArrayInputStream(content.getBytes)
    }

    def getNotebookContentAsString(path: Path): RIO[BaseEnv, String] = readPathAsString( path )

    def getNotebookContentAsByteArray(path: Path): RIO[BaseEnv, Array[Byte]] = {
        val nbInputStream = getNotebookContentStream(path.toFile.getPath)
        for {
            content <- readBytes(nbInputStream)
        }
        yield content.toArray
    }

    // RIO[-R, +A] = ZIO[R, scala.Throwable, A]
    def getNotebookContentAsInputStream(path: Path): RIO[BaseEnv, ByteArrayInputStream] = {
        for {
            stream <- effectBlocking( getNotebookContentStream (path.toFile.getPath) )
        }
        yield stream
    }


    private def removeExtension(path: String) = {
        path.lastIndexOf('.') match {
          case -1 => path  // No extension was found.
          case idx => path.substring(0, idx)
        }
    }

    /**
     * Separate the path element from the name, for a given path
     * string.
     */
    private def extractPathAndName(path: String): (String, String) = {
        var pathRef = path
        /**
         * Ensure the path starts with a '/', establishing a virtual
         * root reference for maintaining folder paths in the database.
         */
        if (!pathRef.substring(0, 1).equals("/")) pathRef = "/" + pathRef

        val pos = pathRef.lastIndexOf("/")
        var extractedPath = pathRef.substring(0, pos + 1)
        val fileName = pathRef.substring(pos + 1, pathRef.length)

        if (extractedPath.endsWith("/") && extractedPath.length > 1) extractedPath = extractedPath.substring(0, extractedPath.length - 1)

        (extractedPath, fileName)
    }

    /* pair of functions that implement upsert */
    override def writeStringToPath(path: Path, content: String): RIO[BaseEnv, Unit] = for {
            _ <- effectBlocking(writeNotebookContentToDatabase(path.toFile.getPath, content)).uninterruptible
        }
        yield ()

    private def writeNotebookContentToDatabase(path: String, content: String): Unit = {
        val pathAndName = extractPathAndName(removeExtension(path))
        database.updateNotebookContent(pathAndName._2, pathAndName._1, content)
    }

    private def readBytes(is: => InputStream): RIO[BaseEnv, Chunk.Bytes] = {
        for {
            env    <- ZIO.environment[BaseEnv]
            ec      = env.get[Blocking.Service].blockingExecutor.asEC
            chunks <- fs2.io.readInputStream[Task](effectBlocking(is).provide(env), 8192, ec, closeAfterUse = true).compile.toChunk.map(_.toBytes)
        }
        yield chunks
    }

    /* Note: we accept a path argument here, but don't use it.  The database is the database. */
    override def list(path: Path): RIO[BaseEnv, List[Path]] = {
        effectBlocking(database.getNotebookList())
    }

    /* Note: given that paths are completely arbitrary in the database, can we dispense with the maxDepth notion? */
    override def validate(path: Path): RIO[BaseEnv, Unit] = {
        if (path.iterator().asScala.length > maxDepth) {
            ZIO.fail(new IllegalArgumentException(s"Input path ($path) too deep, maxDepth is $maxDepth"))
        } else ZIO.unit
    }

    /* pair of functions that implement exists ... move, copy, delete are gated on exists in the caller(s) */
    /*
        override def exists(path: Path): RIO[BaseEnv, Boolean] = effectBlocking(path.toFile.exists())
        note that effectBlocking places this call on a separate thread because it is a blocking call
        see here https://zio.dev/docs/overview/overview_creating_effects
        basically, all database calls, which in JDBC are synchronous, should have effectBlocking
     */
    override def exists(path: Path): RIO[BaseEnv, Boolean] /* = effectBlocking(path.toFile.exists()) */
        = effectBlocking( existsNotebookInDatabase( path.toFile.getPath) )

    private def existsNotebookInDatabase(path: String): Boolean = {
        val pathAndName = extractPathAndName(removeExtension(path))
        database.existsNotebookInDatabase(pathAndName._1, pathAndName._2 )
    }

    /* This is NOT an endpoint in our superclass, so we don't need a version of it here.
        We certainly don't want to be creating any directories.  Stub it out.
    */
    private def createDirs(path: Path): RIO[BaseEnv, Unit] = effectBlocking( () )
        /* Was:  effectBlocking(Files.createDirectories(path.getParent)) */

    /** pair of functions that implement Rename/Move */
    override def move(from: Path, to: Path): RIO[BaseEnv, Unit] =
        effectBlocking( moveNotebookInDatabase(from.toFile.getPath, to.toFile.getPath) ).uninterruptible
               /* was:  createDirs(to).map(_ => Files.move(from, to)) */

    private def moveNotebookInDatabase(pathFrom: String, pathTo: String): Unit = {
        val pathAndNameFrom = extractPathAndName(removeExtension(pathFrom))
        val pathAndNameTo = extractPathAndName(removeExtension(pathTo))
        database.moveNotebookInDatabase(pathAndNameFrom._1, pathAndNameFrom._2, pathAndNameTo._1, pathAndNameTo._2 )
    }

    /* pair of functions that implement Copy */
    override def copy(from: Path, to: Path): RIO[BaseEnv, Unit] =
        effectBlocking( copyNotebookInDatabase(from.toFile.getPath, to.toFile.getPath) ).uninterruptible
        /* was:  createDirs(to).map(_ => Files.copy(from, to))  */

    private def copyNotebookInDatabase(pathFrom: String, pathTo: String): Unit = {
        val pathAndNameFrom = extractPathAndName(removeExtension(pathFrom))
        val pathAndNameTo = extractPathAndName(removeExtension(pathTo))
        database.copyNotebookInDatabase(pathAndNameFrom._1, pathAndNameFrom._2, pathAndNameTo._1, pathAndNameTo._2 )
    }

    /* pair of functions that implement Delete */
    override def delete(path: Path): RIO[BaseEnv, Unit] =
        exists(path).flatMap {
          case false => ZIO.fail(new FileNotFoundException(s"File $path doesn't exist"))
          case true  => effectBlocking( deleteNotebookInDatabase( path.toFile.getPath ) ).uninterruptible
                         /* was:  effectBlocking(Files.delete(path)) */
        }

    private def deleteNotebookInDatabase(path: String ): Unit = {
        val pathAndName = extractPathAndName(removeExtension(path))
        database.deleteNotebookInDatabase(pathAndName._2, pathAndName._1 )
    }

    override def init(path: Path): RIO[BaseEnv, Unit] = {
        dbContext = databaseContext.init()
        database = new Database(dbContext)
        createDirs(path)
    }
    override def createLog(path: Path): RIO[BaseEnv, WAL.WALWriter] =
        effectBlocking(path.getParent.toFile.mkdirs()) *> FileChannelWALWriter(path)

}

/**
 * Localized database access functions.  Here is where the actual database calls are made.
 */
class Database(dbContext: DbContext) {

    import dbContext._


    /**
     * Retrieves a List of Path references to notebooks in the database.
     */
    def getNotebookList(): List[Path] = {
        val notebookItems = quote {
            querySchema[NotebookListItem]("polynote_storage",
                _.name -> "notebook_name",
                _.folderPath -> "relative_folder_path")
        }

        val q = quote {
          notebookItems
        }

        val notebookList = dbContext.run(q)

        for {notebookItem <- notebookList
            // we'll add the .ipynb suffix so they look like polynote, but we'll strip it off again when it comes back to us.
            path = Paths.get(notebookItem.folderPath + "/" + notebookItem.name + ".ipynb")
        }
        yield path
    }

    /**
     * Returns the content of a notebook from the database.
     * NOTE that this returns just the content string, not the whole object.
     */
    def getNotebookContent(name: String, folderPath: String): String = {
        val notebookContent = quote {
            querySchema[NotebookContent]("polynote_storage",
                _.notebookId -> "notebook_uuid",   // <== this is not used here
                _.folderPath -> "relative_folder_path",
                _.name -> "notebook_name",
                _.description -> "notebook_description",
                _.content -> "notebook_contents",
                _.updatedOn -> "updated_date" )

        }

        val q = quote {
            notebookContent.filter(n => n.name.equals(lift(name))
                                      && n.folderPath.equals(lift(folderPath)))
        }

        // despite what the hover over says, this will return a List[NotebookContent]
        val result = dbContext.run(q)

        if (result.size > 0) result(0).content else ""
    }

    /* Implement Upsert
     * Update a specific notebook contents in the database,
     * according to the name and the path of the notebook.
     */
    def updateNotebookContent(name: String, folderPath: String, content: String): Unit = {
        // required if logging is used:  implicit val location = Location("polynote.server.repository.fs", 0, "", "updateNotebookContent")
        val notebookUpdateContent = quote {
            querySchema[NotebookContent]("polynote_storage",
                                            _.notebookId -> "notebook_uuid",   // <== this is not used here
                                            _.folderPath -> "relative_folder_path",
                                            _.name -> "notebook_name",
                                            _.description -> "notebook_description",
                                            _.content -> "notebook_contents",
                                            _.updatedOn -> "updated_date" )
        }

        val q = quote {
            notebookUpdateContent.
              filter(n => n.name.equals(lift(name))
                && n.folderPath.equals(lift(folderPath))).
              update(_.content -> lift(content), _.updatedOn -> lift(new Date))
        }

        // returns the count of rows updated.  there should be only 1.
        val result = dbContext.run(q)
        if (result == 1) {
            ()
        }
        else {
            // if no notebook was updated, then we need to insert it.
            // we'll insert the name for the description because we have no other information on the polynote side.
            // we could use the default contents value if we wanted to parse it out (and it existed)
            insertNotebookContent( folderPath, name, name, content )
        }
    }

    def insertNotebookContent(folderPath: String, name: String, description: String, content: String): Unit = {
        val insertNotebookContent = quote {
            querySchema[NotebookContent]("polynote_storage",
                _.notebookId -> "notebook_uuid",
                _.folderPath -> "relative_folder_path",
                _.name -> "notebook_name",
                _.description -> "notebook_description",
                _.content -> "notebook_contents",
                _.updatedOn -> "updated_date")
        }

        val q = quote {
            insertNotebookContent.insert(
                _.folderPath -> lift( folderPath ),
                _.name -> lift( name ),
                _.description -> lift( description ),
                _.content -> lift(content)).returning( _.notebookId )
        }

        // NOTE, the 3.1.0 version of Quill currently in play does not support anything but returning.
        // Later version support returningGenerated and returning whole records, which might be nice.
        val result = dbContext.run(q)

        // This is here for debug interest. Not clear what comes back if the insert fails.
        val uuidInsertedNotebook = result;

        ()
    }

    /* Implement Exists */
    def existsNotebookInDatabase(folderPath: String, name: String): Boolean = {
        implicit val location = Location("polynote.server.repository.fs", 0, "", "updateNotebookContent")
        val notebookItems = quote {
            querySchema[NotebookListItem]("polynote_storage",
                                                _.folderPath -> "relative_folder_path",
                                                _.name -> "notebook_name" )
        }

        val q = quote {
            notebookItems.
              filter(n => n.name.equals(lift(name))
                && n.folderPath.equals(lift(folderPath)) )
        }

        val result = dbContext.run(q)

        Logging.info("Notebook )[" + folderPath + "/" + name + "] +" +
          (if( result.size == 1 ) "exists." else "does not exist." ) )

        ( result.size == 1 )
    }

    /* Implement Move/Rename */
    def moveNotebookInDatabase(pathFrom: String, nameFrom: String, pathTo: String, nameTo: String): Unit = {
        implicit val location = Location("polynote.server.repository.fs", 0, "", "moveNotebookInDatabase")
        val notebookItems = quote {
            querySchema[NotebookListItem]("polynote_storage",
                                                _.folderPath -> "relative_folder_path",
                                                _.name -> "notebook_name" )
        }
        val q = quote {
            notebookItems.
              filter(n => n.name.equals(lift(nameFrom))
                && n.folderPath.equals(lift(pathFrom)) ).
              update(_.folderPath -> lift(pathTo), _.name -> lift(nameTo))  // simply change the folder an path names.
        }

        /* Note for reference, most DML statements return an integer count from run() as here,
            but SELECT queries return a result set with a size.  It seems obvious, but
            it's also easy to forget.  Here, for example, we check result directly as ( result < 1 ).
            For other queries, we have to remember to check result.size instead.
         */
        val result = dbContext.run(q)

        if (result < 1)
            Logging.warn("Unable to move [" + pathFrom + "/" + nameFrom + "] to [" + pathTo + "/" + nameTo + "].")
    }



    /* Implement Copy
        database.copyNotebookInDatabase(pathAndNameFrom._2, pathAndNameFrom._1, pathAndNameTo._2, pathAndNameTo._1 )

        This way bounces the contents off of here, rather than just doing a function or stored procedure.
        Apparently it is possible to invoke a function on the database that will do this, but we learned too late how.
        So we will combine a get with an insert instead.
    * */
    def copyNotebookInDatabase(pathFrom: String, nameFrom: String, pathTo: String, nameTo: String): Unit = {
        implicit val location = Location("polynote.server.repository.fs", 0, "", "copyNotebookInDatabase")
        /*
            Developer Note:  when you do this kind of schema mapping, you must specify any columns that have
            a different name from the one used in the Entities case class.  Quill will use the name from
            the case class as the default for columns you don't explicitly override here.
         */
        val copyNotebookContent = quote {
            querySchema[NotebookContent]("polynote_storage",
                _.notebookId -> "notebook_uuid",
                _.folderPath -> "relative_folder_path",
                _.name -> "notebook_name",
                _.description -> "notebook_description",
                _.content -> "notebook_contents",
                _.updatedOn -> "updated_date"
                )
        }

        val q = quote {
            copyNotebookContent.
              filter(n => n.name.equals(lift(nameFrom))
                && n.folderPath.equals(lift(pathFrom)) )
        }

        val result = dbContext.run(q)

        if( result.size != 1 )
            throw new FileNotFoundException( );

        // at this point, we have the one to copy
        val ItemToCopy = result(0);
        val UuidToCopy = ItemToCopy.notebookId;         // <== for debugging.

        insertNotebookContent(pathTo,
                              nameTo,
                              ItemToCopy.description,
                              ItemToCopy.content )

        // we don't return the inserted item in this case, not even the UUID.
        // polynote doesn't seem to need it.
    }

    /* Implement Delete
        database.deleteNotebookInDatabase(pathAndName._2, pathAndName._1 )
     */
    def deleteNotebookInDatabase(nameFrom: String, pathFrom: String): Unit = {
        val notebookItems = quote {
            querySchema[NotebookListItem]("polynote_storage",
                                                _.folderPath -> "relative_folder_path",
                                                _.name -> "notebook_name" )

        }

        val q = quote {
            notebookItems.
              filter(n => n.name.equals(lift(nameFrom))
                && n.folderPath.equals(lift(pathFrom)) ).delete
        }

        val result = dbContext.run(q)
    }
}
