import akka.actor._
import akka.actor.ActorRef
import scala.util.Random
import scala.util.control.Breaks._
import scala.concurrent._
import scala.concurrent.duration
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

case class start (numNodes:Int, topology:String, alg:String)
case class done(nodeId:Int, value:String)
case class initNode(nodeId:Int, neighborList:List[ActorRef], allActorsList:List[ActorRef])
case class rumor(msg:String, numofTries:Int)
case class pushSum(msg:String)

class Node extends Actor {
  var neighbors:List[ActorRef] = Nil
  var allActors:List[ActorRef] = Nil
  var parentNode:ActorRef = null
  var nodeID:Int = 0
  var rumorCount = 0
  var localS:Double = 0.0
  var localW:Double = 0.0
  var oldRatio:Double = 0.0
  var ratioChangeCount:Int = 0
  var cancellable:Cancellable = null
  val dur = Duration.create(50, scala.concurrent.duration.MILLISECONDS);
  val delay = Duration.create(0, scala.concurrent.duration.MILLISECONDS);

  
  def sendMessage (msg:String){
    val randomInt = new scala.util.Random
    var randomNodeNum:Int  = 0
    randomNodeNum = randomInt.nextInt(neighbors.length)
    //Form the message and send it to this node
    //println ("Sending: " + nodeID + " " + msg)
    neighbors(randomNodeNum) ! pushSum(msg)
  }
  
  def receive = {
    case initNode(nodeId:Int, neighborList:List[ActorRef], allActorsList:List[ActorRef]) => {
      //println (nodeId + "  " +  neighborList.length)
      neighbors = neighborList
      parentNode = sender
      nodeID = nodeId
      localS = nodeID + 1 //nodeID is zero based
      localW = 1.0
      allActors = allActorsList
    }
    case rumor(msg:String, numofTries:Int) => { //Gossip Implementation
      //println ("msg = " + nodeID + "  "+ msg + rumorCount)
      if (rumorCount == 0) //This node is now aware of the gossip message. This can be told to the manager. 
        parentNode ! done(nodeID, "gossip")
      rumorCount = rumorCount + 1 
      val randomInt = new scala.util.Random
      var randomNodeNum:Int  = -1
      randomNodeNum = randomInt.nextInt(neighbors.length)
        if (rumorCount < numofTries) //Transmit is limited to numofTries times
          neighbors(randomNodeNum) ! rumor(msg, numofTries)
     }
    
    case "pushSumStart" => { 
      var msg:String = null
      //Pick a random node
      if (rumorCount == 0)
      {
        cancellable = context.system.scheduler.schedule(delay, dur,self,"sendMessage")
        rumorCount = 1
      }
      localS = localS 
      localW = localW 
      msg = localS.toString + "," + localW.toString
      sendMessage(msg)
    }
    
    case pushSum (msg:String) => {
      //received message from another node
      //println ("Received: " + nodeID + "  " + msg + "  " + localS + " " + localW)
      var receivedS:Double = 0.0
      var receivedW:Double = 0.0
      var newRatio:Double = 0.0
      var messageArr:Array[String] = null
      if (rumorCount == 0)
      {
        cancellable = context.system.scheduler.schedule(delay, dur,self,"sendMessage")
        rumorCount = 1
      }
      //Separate values from message and convert to float
      messageArr = msg.split(",")
      receivedS = messageArr(0).toDouble
      receivedW = messageArr(1).toDouble
      //Update local S and W 
      localS = localS + receivedS
      localW = localW + receivedW
      //Convergence Check
      newRatio = localS / localW
      //println (nodeID + " " + newRatio)
      if ((newRatio-oldRatio).abs < 0.0000000001)
        	ratioChangeCount = ratioChangeCount + 1

      if (ratioChangeCount > 3)
      {     
        //println ("Convergence reached " + nodeID)
      }
      else if (ratioChangeCount == 3)
        //Convergence
      {
      	ratioChangeCount = ratioChangeCount + 1
        cancellable.cancel
        parentNode ! done(nodeID, oldRatio.toString)     
      }      
    }
    case "sendMessage"=> {
      localS = localS / 2
      localW = localW / 2
      oldRatio = localS / localW
      val msg = localS + "," + localW
      sendMessage(msg)
    }
  }
}

class NodeManager extends Actor {
  var arrAllActors:List[ActorRef] = Nil
  var totalNumNodes:Int = 0
  var numNodesConverged:Int = 0  
  var b = System.currentTimeMillis
  val nodeSystem = ActorSystem ("NodeSystem")

  def setupAllNodes(numNodes:Int){
    var i = 0
    //println (realNumNodes)
    while (i < numNodes ) //Will setup numNodes
    {
      arrAllActors ::= nodeSystem.actorOf(Props[Node], "node" + i)
      i = i + 1
    }
  }
  def setupGrid(numNodes:Int, isImperfect:Boolean) {
    var e = numNodes //Edge nodes
    var realNumNodes = math.pow(e.toDouble, 3).toInt //Total num of nodes in a cubic grid with e number of edge nodes
    var i = 0
    setupAllNodes(realNumNodes)
    i = 0
    var numSquared = math.pow(e, 2).toInt
    while (i < realNumNodes)
    {
      var neighborList:List[ActorRef] = Nil
      
      //Left Neighbor 
    if ((i - 1) >= 0 && ((i) % e != 0) && arrAllActors(i - 1) != null)
    {
      //println ( i + " left" + " i - 1 = " + (i-1) + " i % e = " + (i % e))
      neighborList ::= arrAllActors(i - 1)
    }
    //right neighbor
    if ((i + 1) < realNumNodes && ((i + 1) % e  != 0) && arrAllActors(i + 1) != null)
    {
      //println (i + " right" + " i + 1 = " + (i+1) + " (i+2) % e = " + ((i+2)%e))
      neighborList ::= arrAllActors(i + 1)
    }
    //top neighbor
    if ((i - e) >= 0  && (i/numSquared == (i - e)/numSquared) && arrAllActors(i - e) != null)
    {
      //println (i + " top" + " i - e" + (i-e)) 
      neighborList ::= arrAllActors(i - e)
    }
    //bottom neighbor
    if ((i + e) < realNumNodes  && (i/numSquared == (i + e)/numSquared) && arrAllActors(i + e) != null)
    {
      //println (i + " bottom" + " i + e = " + (i + e))
      neighborList ::= arrAllActors(i + e)
    }
    //Up neighbor
    if ((i + numSquared) < realNumNodes && arrAllActors (i + numSquared) != null)
    {
      //println (i + " up" + " i + numsquared = " + (i+numSquared))
      neighborList ::= arrAllActors(i + numSquared)
    }
    //Down neighbor
    if ((i - numSquared) >= 0 && arrAllActors (i - numSquared) != null)
    {
      //println (i + " down" + " i - numsquared = " + (i - numSquared) )
      neighborList ::= arrAllActors(i - numSquared)
    }
    if (isImperfect) //Imperfect Grid, select one random node
    {
      val randomInt = new scala.util.Random
      var randomNodeNum:Int  = 0
      randomNodeNum = randomInt.nextInt(((realNumNodes-1) - 0 ) + 1).toInt 
      breakable {
          neighborList.foreach(x=> {
        
          if (randomNodeNum < realNumNodes && randomNodeNum > 0 && arrAllActors(randomNodeNum) != null && x != arrAllActors(randomNodeNum))
          {
            neighborList ::= arrAllActors(randomNodeNum)
            //println (i + " Node found " + randomNodeNum)
              break
            }
            else
              randomNodeNum = randomInt.nextInt(((realNumNodes-1)- 0)+1 ).toInt
          })
        }
    }
      arrAllActors(i) ! initNode(i, neighborList, arrAllActors)
      i = i + 1
      
    }
  }
  def receive = {
    case start(numNodes:Int, topology:String, alg:String) => {
     topology.toLowerCase().head match {
        case 'f' => {
          var i = 0
          setupAllNodes(numNodes)
          arrAllActors.foreach(x=> {x ! initNode(i, arrAllActors, arrAllActors) 
            i = i + 1})
          }
        case 'l' => {
          var i = 0
          setupAllNodes(numNodes)
          while (i < numNodes)
          {
            var neighborList:List[ActorRef] = Nil
            if (i != 0 && arrAllActors(i-1) != null)
            {
              neighborList ::= arrAllActors(i-1)
            }
            if (i != numNodes-1 && arrAllActors (i+1) != null)
            {
              neighborList ::= arrAllActors(i+1)
            }
            arrAllActors(i) ! initNode(i, neighborList, arrAllActors)
            i = i + 1
          }
        }
        case '3' | 'g' => {
          setupGrid(numNodes,false)
        }
        case 'i' => {
          setupGrid(numNodes, true)
        }
        case _ => {
          println ("Not a valid topology. Valid Topologies: 1. Full 2. Line. 3. 3D Grid 4. 3D Imperfect Grid")
          System.exit(0)
        }
      }
    
      println ("Topology built. Total Number of nodes = " + arrAllActors.length)
      
      alg.toLowerCase().head match{
      //Gossip Algorithm  
        case 'g' => { 
          b = System.currentTimeMillis
          val totalNodes = arrAllActors.length
          val numofTries = math.pow(totalNodes, 2).toInt
          //val numofTries = 10
          //Pick a random node
          val randomInt = new scala.util.Random
          var randomNodeNum:Int  = 0
          randomNodeNum = randomInt.nextInt(totalNodes)  
          //Start the rumor
          println ("Running Gossip Algorithm...")
          arrAllActors(randomNodeNum) ! rumor("\"Verbal\" Kint is Keyser Soze.", numofTries)
        }
        //Push Sum Algorithm
        case 'p' => {
          b = System.currentTimeMillis
          val totalNodes = arrAllActors.length
          val randomInt = new scala.util.Random
          var randomNodeNum:Int  = 0
          randomNodeNum = randomInt.nextInt(totalNodes)
          println ("Running Push-Sum...")
          arrAllActors(randomNodeNum) ! "pushSumStart"
        }
        //Any other
        case _ => {
          println ("Algorithm not supported")
          System.exit(0)
        }
      }
    }
    case done(nodeID:Int, value:String) => { 
	  val delay = Duration.create(5000, scala.concurrent.duration.MILLISECONDS);
      numNodesConverged = numNodesConverged + 1
      //println ("nodeID:  " + nodeID +" Num done  " + numNodesConverged + "  value = " + value + " Time: " + System.currentTimeMillis)
      if (numNodesConverged == arrAllActors.length)
      {
        if (value != "gossip") //only for push sum. Gossip returns gossip in value
        {
          println ("Final Ratio = " + value + "   " + nodeID)
        }
        println ("Time: " + (System.currentTimeMillis - b) + " ms")
        println ("Finished")
        System.exit(0)
      }
      var timeLastConvergence:Long = System.currentTimeMillis
      context.system.scheduler.scheduleOnce(delay, self, "checkConvergence:"+numNodesConverged.toString+":"+timeLastConvergence.toString+":"+value)
    } 
    case s:String => {
    	val delay = Duration.create(5000, scala.concurrent.duration.MILLISECONDS);
    	var msg = s.split(":")
    	var numNodesConvergedMessage = msg(1).toInt

    	if (numNodesConvergedMessage == numNodesConverged)
    	{
	    	if (msg(3) != "gossip") //only for push sum. Gossip returns gossip in value
	        {
	          println ("Final Ratio = " + msg(3))
	          println ("Time of Last convergence = " + msg(2) + " b = " + b)
	        }
	        println ("Time: " + (msg(2).toLong - b) + " ms")
	        println ("Finished")
	        System.exit(0)
    	}
    	else
    	context.system.scheduler.scheduleOnce(delay, self, "checkConvergence:"+numNodesConverged.toString)
    }
  }
}


object project2 {
  def main (args: Array[String]) {
    if (args.length != 3) //incorrect number of arguments
    {
      println ("Usage: project2 <numNodes> <topology> <algorithm>")
      System.exit(0)
    }
      
    val numNodes:Int = args(0).toInt
    val topology:String = args(1)
    val alg:String = args(2)
    
    //start the manager
    val nodeManSys = ActorSystem("NodeManagerSystem")
    val nodeMan = nodeManSys.actorOf(Props[NodeManager], "nodeMan")
    
    nodeMan ! start (numNodes, topology, alg)
  }  
}