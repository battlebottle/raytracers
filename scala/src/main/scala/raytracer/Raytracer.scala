package raytracer

import scala.util.Try

object Raytracer extends App {

  type Pos = Vec3
  type Dir = Vec3
  val pi = math.Pi.toFloat

  type Color = Vec3
  val black: Color = Vec3(0, 0, 0)
  val white: Color = Vec3(1, 1, 1)

  final case class Ray(origin: Pos, dir: Dir) {

    def pointAtParam(t: Float): Pos = origin + dir.scale(t)

    def aabbHit(aabb: AABB, tMin0: Float, tMax0: Float): Boolean = {
      def go(min_ : Float, max_ : Float, origin_ : Float, dir_ : Float, tMin_ : Float, tMax_ : Float): (Float, Float) = {
        val invD = 1f / dir_
        val t0 = (min_ - origin_) * invD
        val t1 = (max_ - origin_) * invD
        val (t0_, t1_) = if (invD < 0) (t1, t0) else (t0, t1)
        val tMin__ = t0_ max tMin_
        val tMax__ = t1_ min tMax_
        (tMin__, tMax__)
      }
      val (tMin1, tMax1) = go(aabb.min.x, aabb.max.x, origin.x, dir.x, tMin0, tMax0)
      if(tMax1 <= tMin1) false
      else {
        val (tMin2, tMax2) = go(aabb.min.y, aabb.max.y, origin.y, dir.y, tMin1, tMax1)
        if(tMax2 <= tMin2) false
        else {
          val (tMin3, tMax3) = go(aabb.min.z, aabb.max.z, origin.z, dir.z, tMin2, tMax2)
          !(tMax3 <= tMin3)
        }
      }
    }

  }
  final case class Hit(t: Float, p: Pos, normal: Dir, color: Color)
  final case class Sphere(pos: Pos, color: Color, radius: Float) {
    def aabb: AABB = AABB(
      pos - Vec3(radius, radius, radius),
      pos + Vec3(radius, radius, radius))

    def hit(ray: Ray, tMin: Float, tMax: Float): Option[Hit] = {
      val oc = ray.origin - pos
      val a = ray.dir dot ray.dir
      val b = oc dot ray.dir
      val c = (oc dot oc) - radius * radius
      val discriminant = b*b - a*c
      def tryHit(temp: Float) =
        if(temp < tMax && temp > tMin)
          Some(
            Hit( temp
               , ray.pointAtParam(temp)
               , (ray.pointAtParam(temp) - pos).scale(1f / radius)
               , color
               ))
        else None

      if(discriminant <= 0) None
      else tryHit((-b - math.sqrt(b*b - a*c).toFloat) / a) match {
        case Some(hit) => Some(hit)
        case None => tryHit((-b + math.sqrt(b*b - a*c).toFloat)/a)
      }
    }

  }

    

  type Objs = BVH[Sphere]

  def objsHit(objs: Objs, ray: Ray, tMin: Float, tMax: Float): Option[Hit] = objs match {
    case Leaf(_, sphere) =>
      sphere.hit(ray, tMin, tMax)
    case Split(box, left, right) =>
      if(!ray.aabbHit(box, tMin, tMax)) None
      else objsHit(left, ray, tMin, tMax) match {
        case Some(hit1) => Some(objsHit(right, ray, tMin, hit1.t).getOrElse(hit1))
        case None => objsHit(right, ray, tMin, tMax)
      }
  }

  final case class Camera(origin: Pos, llc: Pos, horizontal: Dir, vertical: Dir)

  object Camera {
    def apply(lookFrom: Pos, lookAt: Pos, vUp: Dir, vFov: Float, aspect: Float): Camera = {
      val theta = vFov * pi / 180f
      val halfHeight = math.tan(theta / 2f).toFloat
      val halfWidth = aspect * halfHeight
      val origin = lookFrom
      val w = (lookFrom - lookAt).normalise
      val u = (vUp cross w).normalise
      val v = w cross u
      Camera( lookFrom
            , origin - u.scale(halfWidth) - v.scale(halfHeight) - w
            , u.scale(2*halfWidth)
            , v.scale(2*halfHeight)
            )
    }
  }

  def getRay(cam: Camera, s: Float, t: Float): Ray =
    Ray( cam.origin
       , cam.llc + cam.horizontal.scale(s) + cam.vertical.scale(t) - cam.origin
       )

  def reflect(v: Vec3, n: Vec3): Vec3 =
    v - n.scale(2 * (v dot n))

  def scatter(ray: Ray, hit: Hit): Option[(Ray, Vec3)] = {
    val reflected = reflect(ray.dir.normalise, hit.normal)
    val scattered = Ray(hit.p, reflected)
    if((scattered.dir dot hit.normal) > 0)
      Some((scattered, hit.color)) else None
  }

  def rayColor(objs: Objs, ray: Ray, depth: Int): Color = objsHit(objs, ray, 0.001f, (1.0f / 0.0f)) match {
    case Some(hit) => scatter(ray, hit) match {
      case Some((scattered, attenuation)) if depth < 50 =>
        attenuation * rayColor(objs, scattered, depth+1)
      case _ => black
    }
    case None =>
      val unitDir = (ray.dir).normalise
      val t = 0.5f * (unitDir + Vec3.one).y
      Vec3.one.scale(1f-t) + Vec3(0.5f, 0.7f, 1f).scale(t)
  }

  def traceRay(objs: Objs, width: Int, height: Int, camera: Camera, j: Int, i: Int): Color =
    rayColor(objs,
      getRay(camera,
        i.toFloat / width.toFloat,
        j.toFloat / height.toFloat), 0)

  def colorToPixel(c: Color): Image.Pixel =
    ((255.99f * c.x).toByte, (255.99f * c.y).toByte, (255.99f * c.z).toByte)


  def render(objs: Objs, width: Int, height: Int, camera: Camera): Image =
    Image(width, height, (j, i) =>
        colorToPixel(traceRay(objs, width, height, camera, j, i)))

  def time[R](text: String, block: => R): R = {
    val t0 = System.nanoTime()
    val result = block    // call-by-name
    val t1 = System.nanoTime()
    println(s"$text: ${(t1 - t0)/1000000.0}ms")
    result
  }

  val (scene, width, height) = (args match {
    case Array(s, w, h, _*) => for {
      ss <- Scene.fromString(s)
      ww <- Try(w.toInt).toOption
      hh <- Try(h.toInt).toOption
    } yield (ss, ww, hh)
    case _ => None
  }).getOrElse({
    println("Error parsing command line arguments, running rgbbox 1000x1000 px")
    (Scene.rgbbox, 1000, 1000)
  })

  val (objs, cam) = time("Constructing the scene took", scene.toObjsCam(width, height))
  val out = time("Rendering took", render(objs, width, height, cam).toPPM)

  import java.io._
  val pw = new PrintWriter(new File("out.ppm"))
  pw.write(out)
  println("wrote output to 'out.ppm'")
  pw.close

}
